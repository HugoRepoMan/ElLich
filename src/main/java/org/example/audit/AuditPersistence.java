/*
 * CONTEXTO INICIAL
 * Es el puente transaccional entre los eventos validados y la tabla audit_events.
 * write convierte el lote en entidades y utiliza persist/flush para que Hibernate agrupe
 * los INSERT. recent recupera como máximo 200 registros, con filtro opcional por agente.
 * confirmedSave participa en la misma transacción que el guardado del agente y publica
 * su notificación después del commit. Si esa transacción falla, el cambio y su auditoría
 * confirmada se revierten juntos. View expone los registros sin entregar entidades JPA.
 */
package org.example.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.example.entity.AuditEvent;
import org.example.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.example.audit.AuditTypes.*;

@Service
public class AuditPersistence {
    private final EntityManager em;
    private final ObjectMapper json;
    private final AuditEventRepository repository;
    private final AuditStream stream;
    public AuditPersistence(EntityManager em, ObjectMapper json, AuditEventRepository repository, AuditStream stream) {
        this.em = em; this.json = json; this.repository = repository; this.stream = stream;
    }
    private String encode(Event event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo serializar evento", e); }
    }
    public View view(AuditEvent row) {
        try {
            return new View(row.getId(), row.getContextId(), row.getActor(), row.getPage(), row.getAgentCode(), row.getReceivedAt(),
                    row.getSource(), json.readValue(row.getPayload(), Event.class), row.getGapsBefore());
        } catch (JsonProcessingException e) { throw new IllegalStateException("Evento almacenado inválido", e); }
    }
    @Transactional
    public List<View> write(UUID context, String actor, String page, String agentCode, List<Event> events, long previous) {
        List<View> views = new ArrayList<>(events.size());
        for (Event event : events) {
            var row = new AuditEvent(context, event.seq(), actor, page, agentCode, Source.BROWSER_UNVERIFIED,
                    encode(event), event.seq() - previous - 1);
            em.persist(row);
            previous = event.seq();
            views.add(view(row));
        }
        em.flush(); // Hibernate agrupa INSERTs; una transacción por lote, no por tecla.
        return views;
    }
    @Transactional(readOnly = true)
    public List<View> recent(String agentCode) {
        var rows = agentCode == null ? repository.findTop200ByOrderByReceivedAtDescIdDesc()
                : repository.findTop200ByAgentCodeOrderByReceivedAtDescIdDesc(agentCode);
        return rows.stream().map(this::view).toList();
    }
    /** Debe participar en la misma transacción del cambio de negocio. Si falla, ambos hacen rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmedSave(String actor, UUID sessionReference, String agentCode) {
        Event event = new Event(0, 0, Type.ACTION, "save-agent", null, "AGENTE_GUARDADO", 0, null, null, false);
        AuditEvent row = new AuditEvent(sessionReference, null, actor, "agents", agentCode, Source.SERVER_CONFIRMED, encode(event), 0);
        em.persist(row);
        View view = view(row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { stream.publish(List.of(view)); }
        });
    }
}
