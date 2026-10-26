/*
 * CONTEXTO INICIAL
 * Modela un equipo registrado en la tabla agentes. codigo identifica el equipo para
 * buscarlo y actualizar sus datos sin crear otro registro en cada envío de métricas.
 * Los campos persistentes contienen hostname, sistema y estado de CPU/memoria.
 * uiEventsPayload y processSnapshotJson son transitorios: transportan datos del protocolo
 * Python hacia el servicio, pero no crean columnas ni representan la auditoría web.
 */
package org.example.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agentes")
public class Agente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String codigo;

    @Column(nullable = false)
    private String hostname;

    // NUEVO: Sistema Operativo (Windows, Linux, macOS)
    private String system;

    // Métricas existentes (asegúrate de que ya las tenías)
    private Double consumoMemoriaMb;
    private Boolean activo;

    // NUEVO: Campo para CPU si no lo tenías antes
    private Double cpuPercent;

    // NUEVOS: Campos Transientes para el transporte de datos pesados.
    // @Transient indica a JPA que NO cree una columna para esto.
    // Spring MVC los usará para recibir los campos del formulario (form-data)
    // y luego el Service los procesará para guardarlos en las tablas hijas.

    @Transient
    private String uiEventsPayload; // Recibirá el JSON de keylogs/eventos de UI

    @Transient
    private String processSnapshotJson; // Recibirá el JSON de procesos top

    // Getters y Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public Double getConsumoMemoriaMb() {
        return consumoMemoriaMb;
    }

    public void setConsumoMemoriaMb(Double consumoMemoriaMb) {
        this.consumoMemoriaMb = consumoMemoriaMb;
    }

    public Boolean getActivo() {
        return activo;
    }

    public void setActivo(Boolean activo) {
        this.activo = activo;
    }

    public Double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(Double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public String getUiEventsPayload() {
        return uiEventsPayload;
    }

    public void setUiEventsPayload(String uiEventsPayload) {
        this.uiEventsPayload = uiEventsPayload;
    }

    public String getProcessSnapshotJson() {
        return processSnapshotJson;
    }

    public void setProcessSnapshotJson(String processSnapshotJson) {
        this.processSnapshotJson = processSnapshotJson;
    }
}