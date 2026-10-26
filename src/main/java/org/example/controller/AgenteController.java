package org.example.controller;

import org.example.entity.Agente;
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

    /**
     * GET /agentes
     * Lista todos los agentes registrados en la base de datos.
     * Renderiza la vista principal (lista.html).
     */
    @GetMapping("/agentes")
    public String listarAgentes(Model model) {
        List<Agente> agentes = agenteService.listarTodos();
        model.addAttribute("agentes", agentes);
        return "lista";
    }

    /**
     * POST /agentes/guardar
     * Recibe la telemetría del agente Python en formato form-urlencoded.
     * Spring MVC mapea automáticamente los campos del formulario a la entidad Agente,
     * incluyendo los campos @Transient (uiEventsPayload, processSnapshotJson).
     */
    @PostMapping("/agentes/guardar")
    public String guardarTelemetria(@ModelAttribute Agente agente) {
        // El servicio procesa el upsert del agente y los eventos transientes
        agenteService.procesarTelemetria(agente);

        // Patrón PRG (Post/Redirect/Get) para evitar reenvíos accidentales
        return "redirect:/agentes";
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