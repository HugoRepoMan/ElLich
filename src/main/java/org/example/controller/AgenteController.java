/*
 * CONTEXTO INICIAL
 * Conecta las pantallas de agentes con AgenteService. La lista principal muestra los
 * registros y el formulario; Ver Actividad abre la auditoría web del agente seleccionado
 * y conserva una sección independiente con su historial Python.
 * El guardado desde el navegador usa la identidad autenticada y registra una acción
 * confirmada. La telemetría de máquinas entra por una ruta diferente. El binder limita
 * qué propiedades se pueden asignar desde cada formulario para proteger campos internos.
 */
package org.example.controller;

import org.example.entity.Agente;
import org.example.audit.AuditService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.web.bind.WebDataBinder;
import org.example.entity.EndpointEvent;
import org.example.service.AgenteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class AgenteController {

    @Autowired
    private AgenteService agenteService;

    @Autowired
    private AuditService auditService;

    /** Evita asignación masiva de id y datos de UI arbitrarios desde el formulario web. */
    @InitBinder("agente")
    public void allowedFields(WebDataBinder binder, HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/api/agentes/telemetria"))
            binder.setAllowedFields("codigo", "hostname", "system", "cpuPercent", "consumoMemoriaMb",
                    "activo", "uiEventsPayload", "processSnapshotJson");
        else binder.setAllowedFields("codigo", "hostname", "system", "cpuPercent", "consumoMemoriaMb", "activo");
    }

    /**
     * GET /agentes
     * Lista todos los agentes registrados en la base de datos.
     * Renderiza la vista principal (lista.html).
     */
    @GetMapping({"/", "/agentes"})
    public String listarAgentes(Model model) {
        List<Agente> agentes = agenteService.listarTodos();
        model.addAttribute("agentes", agentes);
        return "lista";
    }

    /**
     * POST /agentes/guardar
     * Guarda desde el formulario autenticado con CSRF y auditoría transaccional.
     * El binder web excluye id y los payloads de interacción del protocolo legado.
     */
    @PostMapping("/agentes/guardar")
    public String guardarTelemetria(@ModelAttribute Agente agente, Principal actor, HttpSession session) {
        agenteService.guardarDesdeWeb(agente, actor.getName(), auditService.sessionReference(session));

        // Patrón PRG (Post/Redirect/Get) para evitar reenvíos accidentales
        return "redirect:/agentes";
    }

    /** Transporte legado separado: autenticación Basic con rol AGENT, sin sesión de navegador. */
    @PostMapping("/api/agentes/telemetria")
    @ResponseBody
    public void guardarDesdeMaquina(@ModelAttribute Agente agente) {
        agenteService.procesarTelemetria(agente);
    }

    /**
     * GET /agentes/{codigo}/auditoria
     * Muestra el historial de eventos de interacción (keylogs/mouse)
     * de un agente específico.
     */
    @GetMapping("/agentes/{codigo}/auditoria")
    public String verAuditoria(@PathVariable String codigo, Model model) {
        // Cargar el agente para mostrar sus metadatos (hostname, system, etc.)
        Agente agente = agenteService.obtenerPorCodigo(codigo);

        // Cargar los 50 eventos más recientes
        List<EndpointEvent> eventos = agenteService.obtenerEventosRecientes(codigo);

        model.addAttribute("agente", agente);
        model.addAttribute("eventos", eventos);

        return "auditoria";
    }
}