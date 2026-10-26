/*
 * CONTEXTO INICIAL
 * Representa una fila del historial web persistente de audit_events. Conserva actor,
 * agente asignado, contexto, fuente, fecha del servidor y contenido normalizado del evento.
 * La combinación contexto/secuencia evita repetir eventos del navegador. La secuencia
 * JPA con bloques de 50 permite agrupar INSERT; los índices ayudan al historial general
 * y al filtrado por agente. La aplicación agrega registros y no expone edición de ellos.
 */
package org.example.entity;

import jakarta.persistence.*;
import org.example.audit.AuditTypes.Source;
import java.time.Instant;
import java.util.UUID;

/** Append-only a nivel de aplicación; sin relaciones ni cascadas en la ruta de escritura. */
@Entity
@Table(name = "audit_events", uniqueConstraints =
        @UniqueConstraint(name = "uq_audit_context_seq", columnNames = {"context_id", "client_sequence"}),
        indexes = {@Index(name = "idx_audit_received", columnList = "received_at"),
                @Index(name = "idx_audit_agent_received", columnList = "agent_code,received_at")})
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_seq")
    @SequenceGenerator(name = "audit_seq", sequenceName = "audit_event_seq", allocationSize = 50)
    private Long id;
    @Column(name = "context_id", nullable = false, updatable = false)
    private UUID contextId;
    @Column(name = "client_sequence", updatable = false)
    private Long clientSequence;
    @Column(nullable = false, length = 100, updatable = false)
    private String actor;
    @Column(nullable = false, length = 32, updatable = false)
    private String page;
    @Column(name = "agent_code", length = 255, updatable = false)
    private String agentCode;
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private Source source;
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String payload;
    @Column(nullable = false, updatable = false)
    private long gapsBefore;

    protected AuditEvent() { }
    public AuditEvent(UUID contextId, Long clientSequence, String actor, String page, String agentCode,
                      Source source, String payload, long gapsBefore) {
        this.contextId = contextId; this.clientSequence = clientSequence;
        this.actor = actor; this.page = page; this.agentCode = agentCode; this.source = source;
        this.payload = payload; this.gapsBefore = gapsBefore; this.receivedAt = Instant.now();
    }
    public Long getId() { return id; }
    public UUID getContextId() { return contextId; }
    public String getActor() { return actor; }
    public String getAgentCode() { return agentCode; }
    public String getPage() { return page; }
    public Instant getReceivedAt() { return receivedAt; }
    public Source getSource() { return source; }
    public String getPayload() { return payload; }
    public long getGapsBefore() { return gapsBefore; }
}
