/*
 * CONTEXTO INICIAL
 * Pruebas unitarias de la admisión y el filtrado de la cola SSE.
 * Llenan una cola con eventos de prueba para comprobar que tiene un tamaño limitado,
 * que conserva el orden y que los eventos menos prioritarios no desplazan confirmaciones.
 * También comprueban que una suscripción de un agente rechaza registros de otros.
 * No abren conexiones de red ni escriben en la base de datos.
 */
package org.example.audit;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.example.audit.AuditTypes.*;

class AuditStreamTest {
    AuditStream.Envelope envelope(int seq, Type type, Source source) {
        return new AuditStream.Envelope("id:" + seq,new View((long)seq,UUID.randomUUID(),"alice","agents",null,
                Instant.now(),source,new Event(seq,1,type,"codigo",null,null,0,null,null,false),0));
    }
    @Test void boundedPriorityAdmissionPreservesDeliveryOrder() throws Exception {
        var s = new AuditStream.Subscriber();
        for (int i=1; i<=256; i++) s.offer(envelope(i,Type.KEY_UP,Source.BROWSER_UNVERIFIED));
        s.offer(envelope(257,Type.CLICK,Source.BROWSER_UNVERIFIED));
        assertThat(s.queue).hasSize(256);
        assertThat(s.takeDropped()).isEqualTo(1);
        assertThat(s.next().id()).isEqualTo("id:2");
        assertThat(s.queue.getLast().id()).isEqualTo("id:257");
    }
    @Test void subscriberRejectsOtherAgentsAndUnassignedEvents() {
        var subscriber = new AuditStream.Subscriber(); subscriber.agentCode = "AGENT-A";
        var event = envelope(1,Type.INPUT,Source.BROWSER_UNVERIFIED).view();
        assertThat(subscriber.accepts(event)).isFalse();
        var assigned = new View(event.id(),event.contextId(),event.actor(),event.page(),"AGENT-A",
                event.receivedAt(),event.source(),event.event(),event.gapsBefore());
        assertThat(subscriber.accepts(assigned)).isTrue();
        subscriber.agentCode = "AGENT-B";
        assertThat(subscriber.accepts(assigned)).isFalse();
        subscriber.agentCode = null;
        assertThat(subscriber.accepts(assigned)).isTrue();
    }
    @Test void lowPriorityDoesNotEvictConfirmedActions() {
        var s = new AuditStream.Subscriber();
        for (int i=1; i<=256; i++) s.offer(envelope(i,Type.ACTION,Source.SERVER_CONFIRMED));
        s.offer(envelope(257,Type.KEY_UP,Source.BROWSER_UNVERIFIED));
        assertThat(s.queue).hasSize(256);
        assertThat(s.queue.getFirst().id()).isEqualTo("id:1");
        assertThat(s.queue.getLast().id()).isEqualTo("id:256");
        assertThat(s.takeDropped()).isEqualTo(1);
    }
}
