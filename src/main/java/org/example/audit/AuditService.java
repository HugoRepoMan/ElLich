/*
 * CONTEXTO INICIAL
 * Coordina la vida de una captura y la aceptación de sus lotes. Cada contexto vincula
 * usuario autenticado, sesión, página y agente. Antes de persistir comprueba permisos
 * del campo, orden de secuencia, repetición del lote y límites de concurrencia.
 * Permite cuatro capturas simultáneas: una concesión de dos minutos se renueva con
 * heartbeat o lotes; la vida máxima es de treinta minutos. Así se liberan cupos abandonados.
 * El mismo lote puede reintentarse sin insertar dos veces. Sólo después del commit
 * se confirma al cliente y se publica en AuditStream. Los contextos viven en memoria;
 * los eventos ya confirmados quedan en PostgreSQL.
 */
package org.example.audit;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.example.repository.AgenteRepository;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.Semaphore;
import static org.example.audit.AuditTypes.*;
import static org.springframework.http.HttpStatus.*;

/** Contexto efímero ligado a sesión autenticada. El nonce no prueba que el evento ocurrió. */
@Service
public class AuditService {
    private static final String ATTRIBUTE = AuditService.class.getName();
    static final long IDLE_SECONDS = 120;
    private final Clock wallClock;
    private final AgenteRepository agents;
    private final AuditPolicy policy;
    private final AuditPersistence persistence;
    private final AuditStream stream;
    private final Semaphore writers = new Semaphore(8);
    @Autowired
    public AuditService(AuditPolicy policy, AuditPersistence persistence, AuditStream stream, AgenteRepository agents) {
        this(policy, persistence, stream, agents, Clock.systemUTC());
    }
    AuditService(AuditPolicy policy, AuditPersistence persistence, AuditStream stream, AgenteRepository agents, Clock clock) {
        this.policy = policy; this.persistence = persistence; this.stream = stream; this.agents = agents;
        this.wallClock = clock;
    }
    static final class Context {
        UUID id = UUID.randomUUID();
        String actor, page, agentCode;
        Map<String, Mode> fields;
        volatile Instant expires, leaseExpires;
        Context(Instant now) {
            expires = now.plusSeconds(1800);
            leaseExpires = now.plusSeconds(IDLE_SECONDS);
        }
        boolean expired(Instant now) { return !now.isBefore(expires) || !now.isBefore(leaseExpires); }
        long sequence;
        UUID batchId;
        byte[] digest;
        Ack ack;
        double tokens = 20;
        long clock = System.nanoTime();
    }
    @SuppressWarnings("unchecked")
    private Map<UUID, Context> contexts(HttpSession session) {
        Map<UUID, Context> contexts = (Map<UUID, Context>) session.getAttribute(ATTRIBUTE);
        if (contexts == null) {
            contexts = new LinkedHashMap<>();
            session.setAttribute(ATTRIBUTE, contexts);
        }
        return contexts;
    }
    public String validateAgent(String code) {
        if (code == null || code.isBlank()) return null;
        if (code.length() > 255 || !agents.findByCodigo(code).isPresent())
            throw new ResponseStatusException(NOT_FOUND, "Agente no encontrado");
        return code;
    }
    public Grant grant(HttpSession session, String actor, String page, String agentCode) {
        String selectedAgent = validateAgent(agentCode);
        if ("agent-audit".equals(page) && selectedAgent == null)
            throw new ResponseStatusException(BAD_REQUEST, "Seleccionar agente para esta captura");
        Map<String, Mode> fields;
        try { fields = policy.fields(page); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, e.getMessage()); }
        synchronized (session) {
            Map<UUID, Context> all = contexts(session);
            Instant now = wallClock.instant();
            all.values().removeIf(c -> c.expired(now));
            if (all.size() >= 4) throw new ResponseStatusException(TOO_MANY_REQUESTS, "Ya hay cuatro capturas activas en esta sesión. Detén una en otra pestaña. Las capturas abandonadas liberan su cupo en un máximo de dos minutos");
            Context c = new Context(now); c.actor = actor; c.page = page; c.fields = fields; c.agentCode = selectedAgent;
            all.put(c.id, c);
            return new Grant(c.id, c.page, fields, 400);
        }
    }
    public void revoke(HttpSession session, String actor, UUID id) {
        Context c;
        synchronized (session) { c = contexts(session).get(id); }
        if (c != null) synchronized (c) {
            if (!c.actor.equals(actor)) throw new ResponseStatusException(FORBIDDEN);
            c.expires = Instant.EPOCH; c.leaseExpires = Instant.EPOCH;
            synchronized (session) { contexts(session).remove(id); }
        }
    }
    /** Renueva sólo un contexto vigente. Una pestaña abandonada no retiene un cupo durante 30 minutos. */
    public void heartbeat(HttpSession session, String actor, UUID id) {
        Context c;
        synchronized (session) { c = contexts(session).get(id); }
        if (c == null) throw new ResponseStatusException(CONFLICT, "Captura caducada; activar nuevamente");
        synchronized (c) {
            if (!c.actor.equals(actor)) throw new ResponseStatusException(FORBIDDEN);
            Instant now = wallClock.instant();
            if (c.expired(now)) throw new ResponseStatusException(CONFLICT, "Captura caducada; activar nuevamente");
            c.leaseExpires = now.plusSeconds(IDLE_SECONDS);
        }
    }
    public UUID sessionReference(HttpSession session) {
        synchronized (session) {
            String key = ATTRIBUTE + ".reference";
            UUID ref = (UUID) session.getAttribute(key);
            if (ref == null) { ref = UUID.randomUUID(); session.setAttribute(key, ref); }
            return ref; // Nunca persistir JSESSIONID, que es una credencial.
        }
    }
    public Ack accept(HttpSession session, String actor, Batch batch, byte[] body) {
        Context c;
        synchronized (session) { c = contexts(session).get(batch.contextId()); }
        if (c == null) throw new ResponseStatusException(CONFLICT, "Contexto perdido; iniciar nueva captura");
        if (!writers.tryAcquire()) throw new ResponseStatusException(TOO_MANY_REQUESTS, "Ingreso ocupado; reintentar");
        try {
            synchronized (c) {
                if (!c.actor.equals(actor) || c.expired(wallClock.instant()))
                    throw new ResponseStatusException(CONFLICT, "Contexto expirado");
                long now = System.nanoTime();
                c.tokens = Math.min(20, c.tokens + (now - c.clock) / 1_000_000_000.0 * 10);
                c.clock = now;
                if (c.tokens < 1) throw new ResponseStatusException(TOO_MANY_REQUESTS, "Límite de lotes por sesión");
                c.tokens--;
                byte[] digest;
                try { digest = MessageDigest.getInstance("SHA-256").digest(body); }
                catch (Exception e) { throw new IllegalStateException(e); }
                if (batch.batchId().equals(c.batchId)) {
                    if (!MessageDigest.isEqual(digest, c.digest))
                        throw new ResponseStatusException(CONFLICT, "Lote repetido con contenido distinto");
                    return c.ack; // Reintento tras perder ACK: no repetir INSERTs ni SSE.
                }
                long previous = c.sequence;
                List<Event> normalized = new ArrayList<>();
                for (Event event : batch.events()) {
                    if (event.seq() <= previous || event.seq() - previous > 100_000)
                        throw new ResponseStatusException(CONFLICT, "Secuencia repetida o fuera de rango");
                    if (Math.abs(event.clientTime() - System.currentTimeMillis()) > 86_400_000L)
                        throw new ResponseStatusException(BAD_REQUEST, "Reloj del cliente fuera de rango");
                    try { normalized.add(policy.normalize(event, c.fields)); }
                    catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, e.getMessage()); }
                    previous = event.seq();
                }
                c.leaseExpires = wallClock.instant().plusSeconds(IDLE_SECONDS);
                // El proxy transaccional retorna sólo después de COMMIT. Si falla, el cliente conserva el lote.
                List<View> committed = persistence.write(c.id, actor, c.page, c.agentCode, normalized, c.sequence);
                c.leaseExpires = wallClock.instant().plusSeconds(IDLE_SECONDS);
                c.sequence = previous; c.batchId = batch.batchId(); c.digest = digest;
                c.ack = new Ack(c.batchId, c.sequence, normalized.size());
                stream.publish(committed);
                return c.ack;
            }
        } finally { writers.release(); }
    }
}
