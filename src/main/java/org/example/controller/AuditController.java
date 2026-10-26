/*
 * CONTEXTO INICIAL
 * Expone las rutas HTTP del módulo de auditoría y entrega la vista general.
 * El cliente solicita un contexto, envía lotes JSON, renueva su concesión con heartbeat
 * y lo revoca al detenerse. Los administradores consultan historial o abren el SSE.
 * El controlador valida el contrato JSON y delega las reglas en AuditService y la lectura
 * en AuditPersistence. Las respuestas de error explican rechazos controlados sin incluir
 * el contenido capturado ni detalles internos de SQL.
 */
package org.example.controller;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import jakarta.validation.Validator;
import org.example.audit.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import static org.example.audit.AuditTypes.*;

@Controller
public class AuditController {
    private final AuditService audit;
    private final AuditPersistence persistence;
    private final AuditStream stream;
    private final ObjectReader reader;
    private final Validator validator;
    public AuditController(AuditService audit, AuditPersistence persistence, AuditStream stream,
                           ObjectMapper json, Validator validator) {
        this.audit = audit; this.persistence = persistence; this.stream = stream; this.validator = validator;
        this.reader = json.readerFor(Batch.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
    @GetMapping({"/admin/auditoria-viva", "/auditoria"}) public String dashboard() { return "audit-live"; }
    @PostMapping("/api/audit/context") @ResponseBody
    public Grant context(@RequestParam String page, @RequestParam(required = false) String agentCode, HttpSession session, Principal actor) {
        return audit.grant(session, actor.getName(), page, agentCode);
    }
    @PostMapping("/api/audit/context/{id}/heartbeat") @ResponseBody
    public ResponseEntity<Void> heartbeat(@PathVariable UUID id, HttpSession session, Principal actor) {
        audit.heartbeat(session, actor.getName(), id);
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/api/audit/context/{id}") @ResponseBody
    public ResponseEntity<Void> revoke(@PathVariable UUID id, HttpSession session, Principal actor) {
        audit.revoke(session, actor.getName(), id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping(value = "/api/audit/batch", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    public Ack receive(@RequestBody byte[] body, HttpSession session, Principal actor) {
        Batch batch;
        try { batch = reader.readValue(body); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON de auditoría inválido"); }
        if (batch == null || !validator.validate(batch).isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lote inválido; máximo 64 eventos");
        return audit.accept(session, actor.getName(), batch, body);
    }
    @GetMapping("/api/audit/history") @ResponseBody
    public List<View> history(@RequestParam(required = false) String agentCode) {
        return persistence.recent(audit.validateAgent(agentCode));
    }
    @GetMapping(value = "/api/audit/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String last,
                             @RequestParam(required = false) String agentCode, HttpSession session, Principal actor, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        return stream.subscribe(last, session, actor.getName(), audit.validateAgent(agentCode));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException error) {
        // Sólo razones controladas de esta API; no serializar payloads ni excepciones internas.
        String reason = error.getReason() == null ? "Solicitud de auditoría rechazada" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", reason));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).header("Retry-After", "2")
                .body(Map.of("error", "Persistencia temporalmente no disponible; reintentar el mismo lote"));
    }
}
