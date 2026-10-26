/*
 * CONTEXTO INICIAL
 * Distribuye por Server-Sent Events los registros que el servidor ya ha confirmado.
 * Cada administrador tiene una cola acotada y un escritor independiente del ingreso
 * y de JDBC. Si hay saturación, la prioridad decide qué eventos conservar y la entrega
 * mantiene su orden. El filtro por agente se aplica antes de encolar cada evento.
 * Un anillo en memoria permite reconexiones breves; las pérdidas se notifican como gap.
 * PostgreSQL es el historial durable: el SSE y su anillo sólo sirven para la vista en vivo.
 */
package org.example.audit;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.example.audit.AuditTypes.*;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/** SSE aislado de JDBC. Colas y número de escritores acotados; nunca enviar en el hilo de ingreso. */
@Component
public class AuditStream {
    static final int CAPACITY = 256;
    private final String epoch = UUID.randomUUID().toString();
    private long sequence;
    private final Deque<Envelope> replay = new ArrayDeque<>();
    private final Set<Subscriber> subscribers = new HashSet<>();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private boolean stopped;
    record Envelope(String id, View view) { }

    static int priority(View v) {
        if (v.source() == Source.SERVER_CONFIRMED) return 4;
        return switch (v.event().type()) {
            case INPUT, CHANGE, CLICK -> 3;
            case KEY_DOWN -> 2;
            default -> 1;
        };
    }

    static final class Subscriber {
        final Deque<Envelope> queue = new ArrayDeque<>();
        final SseEmitter emitter = new SseEmitter(60_000L);
        String actor, agentCode;
        boolean accepts(View view) { return agentCode == null || agentCode.equals(view.agentCode()); }
        final AtomicBoolean closed = new AtomicBoolean();
        long dropped;
        synchronized void offer(Envelope incoming) {
            if (closed.get()) return;
            if (queue.size() == CAPACITY) {
                Envelope victim = queue.stream().min(Comparator.comparingInt(e -> priority(e.view()))).orElseThrow();
                dropped++;
                if (priority(victim.view()) > priority(incoming.view())) return;
                queue.remove(victim);
            }
            queue.addLast(incoming); // Prioridad para admisión; orden temporal para reconstrucción.
            notifyAll();
        }
        synchronized Envelope next() throws InterruptedException {
            if (queue.isEmpty() && !closed.get()) wait(10_000L);
            return queue.pollFirst();
        }
        synchronized long takeDropped() { long n = dropped; dropped = 0; return n; }
        synchronized void wake() { notifyAll(); }
    }

    public synchronized void publish(List<View> views) {
        if (stopped) return;
        for (View view : views) {
            Envelope e = new Envelope(epoch + ":" + (++sequence), view);
            replay.addLast(e);
            if (replay.size() > 4096) replay.removeFirst();
            for (Subscriber s : subscribers) if (s.accepts(view)) s.offer(e);
        }
    }

    public SseEmitter subscribe(String lastId, HttpSession session, String actor, String agentCode) {
        Subscriber s = new Subscriber();
        synchronized (this) {
            long own = subscribers.stream().filter(x -> x.actor.equals(actor)).count();
            if (stopped || subscribers.size() >= 16 || own >= 2)
                throw new ResponseStatusException(TOO_MANY_REQUESTS, "Límite de observadores SSE");
            s.actor = actor; s.agentCode = agentCode;
            if (lastId != null && !lastId.isBlank()) {
                List<Envelope> history = new ArrayList<>(replay);
                int index = -1;
                for (int i = 0; i < history.size(); i++) if (history.get(i).id().equals(lastId)) index = i;
                if (index < 0) s.dropped++;
                else for (int i = index + 1; i < history.size(); i++) {
                    Envelope entry = history.get(i);
                    if (s.accepts(entry.view())) s.offer(entry);
                }
            }
            subscribers.add(s);
        }
        s.emitter.onCompletion(() -> close(s));
        s.emitter.onTimeout(() -> close(s));
        s.emitter.onError(error -> close(s));
        senders.submit(() -> {
            try {
                s.emitter.send(SseEmitter.event().name("ready").data("connected").reconnectTime(1500));
                while (!s.closed.get()) {
                    Envelope e = s.next();
                    if (s.closed.get()) break;
                    // Una conexión larga no conserva acceso después del logout o de perder el rol.
                    if (!isAdmin(session, actor)) break;
                    long dropped = s.takeDropped();
                    if (dropped > 0) s.emitter.send(SseEmitter.event().name("gap").data(dropped));
                    if (e == null) s.emitter.send(SseEmitter.event().comment("heartbeat"));
                    else s.emitter.send(SseEmitter.event().id(e.id()).name("audit").data(e.view()));
                }
            } catch (Exception ignored) {
                // Desconexión o fallo de escritura: termina sólo este observador.
            } finally { close(s); s.emitter.complete(); }
        });
        return s.emitter;
    }

    private boolean isAdmin(HttpSession session, String actor) {
        try {
            Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context) || context.getAuthentication() == null) return false;
            var auth = context.getAuthentication();
            return auth.isAuthenticated() && actor.equals(auth.getName()) &&
                    auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        } catch (IllegalStateException expired) { return false; }
    }
    private void close(Subscriber s) {
        if (s.closed.compareAndSet(false, true)) {
            synchronized (this) { subscribers.remove(s); }
            s.wake();
        }
    }
    @PreDestroy public synchronized void shutdown() {
        stopped = true;
        for (Subscriber s : List.copyOf(subscribers)) { close(s); s.emitter.complete(); }
        senders.shutdownNow();
    }
}
