/*
 * CONTEXTO INICIAL
 * Es la lista de campos y eventos que el servidor permite capturar en cada página.
 * Antes de guardar, normalize comprueba el destino, descarta datos que no corresponden
 * al tipo de evento y sustituye los datos de campos sensibles por una máscara fija.
 * Para habilitar un campo nuevo también debe existir su data-audit-id en la plantilla.
 * Esta validación restringe la telemetría; no demuestra que una tecla o un clic ocurrieron.
 */
package org.example.audit;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import static org.example.audit.AuditTypes.*;

/** La lista de campos es del servidor. No aceptar selectores o páginas arbitrarios. */
@Component
public class AuditPolicy {
    private static final Map<String, Mode> AGENTS = Map.ofEntries(
            Map.entry("codigo", Mode.TEXT), Map.entry("hostname", Mode.TEXT), Map.entry("system", Mode.TEXT),
            Map.entry("cpuPercent", Mode.CONTROL), Map.entry("consumoMemoriaMb", Mode.CONTROL),
            Map.entry("save-agent", Mode.ACTION), Map.entry("demo-choice", Mode.CONTROL),
            Map.entry("demo-radio-a", Mode.CONTROL), Map.entry("demo-radio-b", Mode.CONTROL),
            Map.entry("demo-click", Mode.ACTION), Map.entry("demo-notes", Mode.TEXT), Map.entry("demo-secret", Mode.REDACTED));
    private static final Set<Type> KEYS = Set.of(Type.KEY_DOWN, Type.KEY_UP, Type.INPUT);
    private static final Set<String> SPECIAL = Set.of("Enter", "Backspace", "Delete", "Tab", "Escape",
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End", "PageUp", "PageDown",
            "Shift", "Control", "Alt", "Meta", "AltGraph", "CapsLock", "Dead", "Unidentified");

    public Map<String, Mode> fields(String page) {
        if ("agents".equals(page)) return AGENTS;
        if ("agent-audit".equals(page)) return Map.of(
                "demo-notes", Mode.TEXT, "demo-choice", Mode.CONTROL,
                "demo-click", Mode.ACTION, "demo-secret", Mode.REDACTED);
        throw new IllegalArgumentException("Página no autorizada");
    }

    public Event normalize(Event e, Map<String, Mode> fields) {
        Mode mode = fields.get(e.elementId());
        if (mode == null || e.type() == null || e.type() == Type.ACTION)
            throw new IllegalArgumentException("Evento fuera del contexto autorizado");
        if (KEYS.contains(e.type()) && mode != Mode.TEXT && mode != Mode.REDACTED)
            throw new IllegalArgumentException("Campo sin permiso de captura de texto");
        if (e.type() == Type.CLICK && mode != Mode.ACTION && mode != Mode.CONTROL)
            throw new IllegalArgumentException("Destino de clic no autorizado");
        if (e.type() == Type.CHANGE && mode != Mode.CONTROL)
            throw new IllegalArgumentException("Cambio no autorizado");
        // Doble barrera: incluso un cliente modificado nunca puede persistir un secreto aquí.
        if (mode == Mode.REDACTED)
            return new Event(e.seq(), e.clientTime(), e.type(), e.elementId(), null,
                    "[REDACTED]", 0, null, null, false);
        String key = null, value = null;
        Integer start = null, end = null;
        if (e.type() == Type.KEY_DOWN || e.type() == Type.KEY_UP) {
            key = e.key();
            if (key == null || (!SPECIAL.contains(key) && key.codePointCount(0, key.length()) != 1))
                throw new IllegalArgumentException("Tecla inválida");
        }
        if (e.type() == Type.INPUT || e.type() == Type.FOCUS || e.type() == Type.BLUR) {
            if (mode == Mode.TEXT) {
                value = e.value();
                start = e.selectionStart(); end = e.selectionEnd();
                if (value == null || start == null || end == null || start > end || end > value.length())
                    throw new IllegalArgumentException("Selección inválida");
            }
        }
        if (e.type() == Type.CHANGE) value = e.value();
        // Nunca conservar valores arbitrarios en CLICK ni datos extra ocultos en eventos.
        return new Event(e.seq(), e.clientTime(), e.type(), e.elementId(), key, value,
                e.modifiers(), start, end, e.repeat());
    }
}
