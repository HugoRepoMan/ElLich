package org.example.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "endpoint_events", indexes = {
        @Index(name = "idx_event_agente_id", columnList = "agente_id"),
        @Index(name = "idx_event_timestamp", columnList = "occurred_at_ms")
})
public class EndpointEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Relación Many-to-One con Agente
    // fetch = LAZY evita cargar los eventos automáticamente al listar agentes
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agente_id", nullable = false)
    private Agente agente;

    // Timestamp en milisegundos para alta precisión (evita LocalDateTime por rendimiento en inserción masiva)
    @Column(name = "occurred_at_ms", nullable = false)
    private Long occurredAtMs;

    // Tipo de entrada: "KEYBOARD", "MOUSE", etc.
    @Column(name = "input_type", length = 20)
    private String inputType;

    // El valor capturado (la tecla, coordenada, etc.)
    @Column(name = "event_value", length = 100)
    private String value;

    // Modificadores (CTRL, SHIFT, etc.) o metadatos
    @Column(name = "modifiers", length = 100)
    private String modifiers;

    // Título de la ventana activa en el momento del evento
    @Column(name = "active_window", length = 255)
    private String activeWindow;

    // Getters y Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Agente getAgente() {
        return agente;
    }

    public void setAgente(Agente agente) {
        this.agente = agente;
    }

    public Long getOccurredAtMs() {
        return occurredAtMs;
    }

    public void setOccurredAtMs(Long occurredAtMs) {
        this.occurredAtMs = occurredAtMs;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getModifiers() {
        return modifiers;
    }

    public void setModifiers(String modifiers) {
        this.modifiers = modifiers;
    }

    public String getActiveWindow() {
        return activeWindow;
    }

    public void setActiveWindow(String activeWindow) {
        this.activeWindow = activeWindow;
    }
}