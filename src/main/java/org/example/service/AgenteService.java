/*
 * CONTEXTO INICIAL
 * Gestiona los equipos registrados y el protocolo de telemetría Python.
 * Busca el equipo por código, inserta o actualiza sus métricas y convierte el payload
 * legado de interacciones en filas EndpointEvent. Ofrece consultas para las vistas.
 * guardarDesdeWeb añade la acción confirmada del guardado en la misma transacción.
 * Las teclas y eventos del nuevo capturador web pasan por AuditService, no por este parser.
 */
package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.Agente;
import org.example.audit.AuditPersistence;
import java.util.UUID;
import org.example.entity.EndpointEvent;
import org.example.repository.AgenteRepository;
import org.example.repository.EndpointEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgenteService {

    @Autowired
    private AgenteRepository agenteRepository;

    @Autowired
    private EndpointEventRepository endpointEventRepository;

    @Autowired
    private AuditPersistence auditPersistence;

    /** Cambio de negocio y auditoría confirmada se guardan en la misma transacción. */
    @Transactional
    public void guardarDesdeWeb(Agente agente, String actor, UUID sessionReference) {
        procesarTelemetria(agente);
        auditPersistence.confirmedSave(actor, sessionReference, agente.getCodigo());
    }

    // ObjectMapper para deserializar los payloads JSON enviados por el agente Python
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Lista todos los agentes registrados.
     */
    public List<Agente> listarTodos() {
        return agenteRepository.findAll();
    }

    /**
     * Procesa la telemetría recibida del agente.
     * 1. Realiza el Upsert del Agente (identidad y métricas base).
     * 2. Parsea y persiste los eventos de interfaz de usuario (keylogs/mouse).
     * 3. (Opcional) Procesa la instantánea de procesos.
     *
     * @param agenteRecibido Entidad Agente con campos @Transient llenos de datos JSON
     */
    @Transactional
    public void procesarTelemetria(Agente agenteRecibido) {

        // 1. UPSERT DEL AGENTE (Identidad y Estado)
        Agente agente = agenteRepository.findByCodigo(agenteRecibido.getCodigo())
                .orElseGet(() -> {
                    Agente nuevo = new Agente();
                    nuevo.setCodigo(agenteRecibido.getCodigo());
                    nuevo.setHostname(agenteRecibido.getHostname());
                    return nuevo;
                });

        // Actualizar campos de estado y métricas
        agente.setHostname(agenteRecibido.getHostname());
        agente.setSystem(agenteRecibido.getSystem());
        agente.setConsumoMemoriaMb(agenteRecibido.getConsumoMemoriaMb());
        agente.setCpuPercent(agenteRecibido.getCpuPercent());
        agente.setActivo(agenteRecibido.getActivo());

        // Guardar el agente (UPDATE si existe, INSERT si es nuevo)
        agenteRepository.save(agente);

        // 2. PROCESAR EVENTOS DE INTERFAZ (Keylogs/Mouse)
        String uiPayload = agenteRecibido.getUiEventsPayload();
        if (uiPayload != null && !uiPayload.isEmpty() && !uiPayload.equals("[]")) {
            try {
                // Deserializar JSON a lista de Maps (estructura flexible)
                List<Map<String, Object>> eventMaps = objectMapper.readValue(
                        uiPayload,
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                // Mapear Maps a Entidades EndpointEvent
                List<EndpointEvent> eventos = new ArrayList<>();
                for (Map<String, Object> map : eventMaps) {
                    EndpointEvent event = new EndpointEvent();
                    event.setAgente(agente);

                    // Extraer campos con manejo de nulls
                    event.setOccurredAtMs(map.get("ts") != null ? ((Number) map.get("ts")).longValue() : System.currentTimeMillis());
                    event.setInputType("KEYBOARD"); // Por defecto KEYBOARD, el Python puede enviar "MOUSE"
                    if (map.containsKey("inputType")) {
                        event.setInputType((String) map.get("inputType"));
                    }

                    event.setValue(map.get("key") != null ? (String) map.get("key") : "");
                    event.setModifiers(map.get("modifiers") != null ? String.join("+", (List<String>) map.get("modifiers")) : "");
                    event.setActiveWindow(map.get("window") != null ? (String) map.get("window") : "Unknown");

                    eventos.add(event);
                }

                // Guardar en lote (Batch Insert) para mejor rendimiento
                if (!eventos.isEmpty()) {
                    endpointEventRepository.saveAll(eventos);
                }
            } catch (Exception e) {
                // Si el JSON está malformado, no rompemos la transacción de las métricas
                System.err.println("[ERROR] Fallo al parsear UI Events: " + e.getMessage());
            }
        }
    }

    /**
     * Recupera los eventos más recientes de un agente para la vista de auditoría.
     *
     * @param codigo Código único del agente
     * @return Lista de eventos (máximo 50)
     */
    public List<EndpointEvent> obtenerEventosRecientes(String codigo) {
        // Pedimos solo las 50 primeras páginas (índice 0) de 50 elementos
        Pageable pageable = PageRequest.of(0, 50);
        return endpointEventRepository.findRecentEventsByAgentCode(codigo, pageable);
    }
    /**
     * Recupera un agente por su código único.
     */
    public Agente obtenerPorCodigo(String codigo) {
        return agenteRepository.findByCodigo(codigo)
                .orElseThrow(() -> new IllegalArgumentException("Agente no encontrado: " + codigo));
    }
}