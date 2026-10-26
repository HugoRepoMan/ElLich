/*
 * CONTEXTO INICIAL
 * Define el lenguaje común del módulo: tipos de evento y objetos de entrada y salida.
 * El navegador envía Event dentro de Batch; Grant autoriza una captura y Ack confirma
 * el lote persistido. View contiene la información que reciben el historial y el SSE.
 * Las anotaciones de validación limitan tamaños y rangos. La identidad del usuario y
 * la fuente de confianza se asignan en el servidor, no en el cuerpo enviado por el cliente.
 */
package org.example.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos acotados: nunca aceptar actor, prioridad o fuente desde el cliente. */
public final class AuditTypes {
    private AuditTypes() { }
    public enum Type { KEY_DOWN, KEY_UP, INPUT, CLICK, CHANGE, FOCUS, BLUR, ACTION }
    public enum Mode { TEXT, REDACTED, CONTROL, ACTION }
    public enum Source { BROWSER_UNVERIFIED, SERVER_CONFIRMED }

    public record Event(
            @Min(1) long seq, @Positive long clientTime,
            @NotNull Type type,
            @NotBlank @Size(max = 64) String elementId,
            @Size(max = 40) String key, @Size(max = 512) String value,
            @Min(0) @Max(31) int modifiers,
            @Min(0) @Max(512) Integer selectionStart,
            @Min(0) @Max(512) Integer selectionEnd,
            boolean repeat) { }
    public record Batch(@NotNull UUID contextId, @NotNull UUID batchId,
                        @NotEmpty @Size(max = 64) List<@NotNull @Valid Event> events) { }
    public record Grant(UUID contextId, String page, Map<String, Mode> fields, int flushMs) { }
    public record Ack(UUID batchId, long lastSequence, int accepted) { }
    public record View(Long id, UUID contextId, String actor, String page, String agentCode, Instant receivedAt,
                       Source source, Event event, long gapsBefore) { }
}
