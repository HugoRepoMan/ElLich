/*
 * CONTEXTO INICIAL
 * Prueba el ciclo de vida de las capturas con un reloj controlado, sin esperas reales.
 * Avanza el tiempo para verificar la liberación de cupos abandonados, la renovación
 * por heartbeat y la vida máxima del contexto. Conserva el límite de cuatro capturas
 * vivas y comprueba que otro actor no pueda renovarlas. Repositorios y servicios
 * externos se sustituyen por mocks: estas pruebas no alteran eventos persistidos.
 */
package org.example.audit;

import org.example.repository.AgenteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditContextLifecycleTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    final MutableClock clock = new MutableClock();
    final AuditService service = new AuditService(new AuditPolicy(),mock(AuditPersistence.class),
            mock(AuditStream.class),mock(AgenteRepository.class),clock);
    final MockHttpSession session = new MockHttpSession();
    AuditTypes.Grant grant() { return service.grant(session,"alice","agents",null); }
    void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
    @Test void fourActiveCapturesStillEnforceTheLimit() {
        for (int i=0; i<4; i++) grant();
        assertStatus(429, () -> grant());
    }
    @Test void abandonedCapturesReleaseSlotsWithoutWaitingThirtyMinutes() {
        var old = grant(); for (int i=0; i<3; i++) grant();
        clock.advance(121);
        assertThat(grant().contextId()).isNotEqualTo(old.contextId());
        assertStatus(409, () -> service.heartbeat(session,"alice",old.contextId()));
    }
    @Test void heartbeatPreservesAnIdleButOpenCapture() {
        var active = grant(); for (int i=0; i<3; i++) grant();
        clock.advance(60); service.heartbeat(session,"alice",active.contextId());
        clock.advance(61);
        for (int i=0; i<3; i++) grant();
        service.heartbeat(session,"alice",active.contextId());
        assertStatus(429, () -> grant());
    }
    @Test void stopReleasesTheSlotImmediatelyAndAnotherActorCannotRenew() {
        var first = grant(); for (int i=0; i<3; i++) grant();
        assertStatus(403, () -> service.heartbeat(session,"bob",first.contextId()));
        service.revoke(session,"alice",first.contextId());
        assertThat(grant().contextId()).isNotEqualTo(first.contextId());
    }
    @Test void heartbeatCannotExtendMaximumLifetimeForever() {
        var first = grant();
        for (int i=0; i<29; i++) { clock.advance(60); service.heartbeat(session,"alice",first.contextId()); }
        clock.advance(59); service.heartbeat(session,"alice",first.contextId());
        clock.advance(1);
        assertStatus(409, () -> service.heartbeat(session,"alice",first.contextId()));
        assertThat(grant().contextId()).isNotEqualTo(first.contextId());
    }
}
