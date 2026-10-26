/*
 * CONTEXTO INICIAL
 * Pruebas de integración del módulo mediante Spring Boot, MockMvc y H2.
 * Ejercitan autenticación, CSRF, origen, contexto, límites, redacción, secuencias,
 * idempotencia, recuperación tras fallo de base de datos y rollback del negocio.
 * También verifican la vista de un agente y que su historial no incluya capturas ajenas.
 * La base de prueba es independiente de PostgreSQL y sus datos no son eventos del usuario.
 */
package org.example.audit;

import com.fasterxml.jackson.databind.*;
import org.example.repository.*;
import org.example.entity.Agente;
import org.example.service.AgenteService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 PostgreSQL mode valida el flujo completo sin requerir una instancia local. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.audit.admin-password=test-admin-password-123", "app.audit.agent-password=test-agent-password-123"
})
@AutoConfigureMockMvc
class LiveAuditIntegrationTest {
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuditEventRepository events;
    @Autowired AgenteRepository agents;
    @Autowired AgenteService agentsService;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean AuditPersistence persistence;
    @MockitoSpyBean AuditStream stream;
    MockHttpSession session;

    @BeforeEach void setup() {
        events.deleteAll();
        clearInvocations(stream);
        session = new MockHttpSession();
    }
    String context() throws Exception {
        return json.readTree(mvc.perform(post("/api/audit/context?page=agents").session(session)
                .with(user("alice").roles("USER")).with(csrf().asHeader())
                .header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("contextId").asText();
    }
    Map<String,Object> event(long seq, String type, String id, String value) {
        var event = new LinkedHashMap<String,Object>();
        event.put("seq",seq); event.put("clientTime",System.currentTimeMillis());
        event.put("type",type); event.put("elementId",id); event.put("value",value);
        event.put("key",null); event.put("modifiers",0); event.put("repeat",false);
        event.put("selectionStart",0); event.put("selectionEnd",value == null ? 0 : value.length());
        return event;
    }
    byte[] batch(String context, Map<String,Object> event) throws Exception {
        return json.writeValueAsBytes(Map.of("contextId",context,"batchId",UUID.randomUUID(),"events",List.of(event)));
    }
    ResultActions send(byte[] body) throws Exception {
        return mvc.perform(post("/api/audit/batch").session(session).with(user("alice").roles("USER"))
                .with(csrf().asHeader()).header("Origin","http://localhost:8080")
                .contentType("application/json").content(body));
    }
    @Test void adminOnlyAndCsrfAndOrigin() throws Exception {
        mvc.perform(get("/api/audit/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit/history").with(user("operator").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/auditoria-viva").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("audit-live"));
        mvc.perform(post("/api/audit/context?page=agents").with(user("alice"))
                .header("Origin","http://localhost:8080")).andExpect(status().isForbidden());
        mvc.perform(post("/api/audit/context?page=agents").with(user("alice")).with(csrf().asHeader())
                .header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/agentes").with(user("alice").roles("USER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Activar captura")));
    }
    @Test void activationLimitExplainsItselfAndStopFreesSlot() throws Exception {
        String first = context(); for (int i=0; i<3; i++) context();
        mvc.perform(post("/api/audit/context?page=agents").session(session).with(user("alice").roles("USER"))
                .with(csrf().asHeader()).header("Origin","http://localhost:8080"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("error").value(org.hamcrest.Matchers.containsString("cuatro capturas activas")));
        mvc.perform(post("/api/audit/context/{id}/heartbeat",first).session(session).with(user("alice").roles("USER"))
                .with(csrf().asHeader()).header("Origin","http://localhost:8080"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/audit/context/{id}",first).session(session).with(user("alice").roles("USER"))
                .with(csrf().asHeader()).header("Origin","http://localhost:8080"))
                .andExpect(status().isNoContent());
        assertThat(context()).isNotEqualTo(first);
    }
    @Test void idempotentAckAndServerOwnedIdentity() throws Exception {
        byte[] body = batch(context(),event(1,"INPUT","codigo","demo"));
        send(body).andExpect(status().isOk()).andExpect(jsonPath("accepted").value(1));
        send(body).andExpect(status().isOk());
        assertThat(events.count()).isEqualTo(1);
        var row = events.findAll().getFirst();
        assertThat(row.getActor()).isEqualTo("alice");
        assertThat(row.getSource()).isEqualTo(AuditTypes.Source.BROWSER_UNVERIFIED);
        verify(stream,times(1)).publish(anyList());
        JsonNode modified = json.readTree(body);
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.get("events").get(0)).put("value","fake");
        send(json.writeValueAsBytes(modified)).andExpect(status().isConflict());
    }
    @Test void redactsEvenMaliciousSecretPayloadAndRejectsTrustedClaims() throws Exception {
        String ctx = context();
        var secret = event(1,"INPUT","demo-secret","password-never-store");
        secret.put("key","x"); secret.put("modifiers",15);
        send(batch(ctx,secret)).andExpect(status().isOk());
        String payload = events.findAll().getFirst().getPayload();
        assertThat(payload).contains("[REDACTED]").doesNotContain("password-never-store");
        assertThat(json.readTree(payload).get("key").isNull()).isTrue();
        var action = event(2,"ACTION","save-agent","FAKE_DELETE");
        send(batch(ctx,action)).andExpect(status().isBadRequest());
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(batch(ctx,event(2,"CLICK","save-agent",null)));
        root.put("source","SERVER_CONFIRMED");
        send(json.writeValueAsBytes(root)).andExpect(status().isBadRequest());
        assertThat(events.count()).isEqualTo(1);
    }
    @Test void validatesContextSequenceAndPayloadLimits() throws Exception {
        String ctx = context();
        send(batch(ctx,event(1,"CLICK","unknown-delete",null))).andExpect(status().isBadRequest());
        send(batch(UUID.randomUUID().toString(),event(1,"CLICK","save-agent",null))).andExpect(status().isConflict());
        send(batch(ctx,event(2,"INPUT","codigo","ok"))).andExpect(status().isOk());
        assertThat(events.findAll().getFirst().getGapsBefore()).isEqualTo(1);
        send(batch(ctx,event(1,"INPUT","codigo","old"))).andExpect(status().isConflict());
        byte[] tooMany = json.writeValueAsBytes(Map.of("contextId",ctx,"batchId",UUID.randomUUID(),
                "events",Collections.nCopies(65,event(3,"CLICK","save-agent",null))));
        send(tooMany).andExpect(status().isBadRequest());
        send(new byte[65537]).andExpect(status().isPayloadTooLarge());
        MockHttpSession other = new MockHttpSession();
        mvc.perform(post("/api/audit/batch").session(other).with(user("alice").roles("USER"))
                .with(csrf().asHeader()).header("Origin","http://localhost:8080")
                .contentType("application/json").content(batch(ctx,event(3,"CLICK","save-agent",null))))
                .andExpect(status().isConflict());
    }
    @Test void failedDatabaseWriteCanRetryIdenticalBatch() throws Exception {
        byte[] body = batch(context(),event(1,"INPUT","codigo","retry"));
        doThrow(new DataAccessResourceFailureException("offline")).doCallRealMethod()
                .when(persistence).write(any(),anyString(),anyString(),nullable(String.class),anyList(),anyLong());
        send(body).andExpect(status().isServiceUnavailable());
        assertThat(events.count()).isZero();
        send(body).andExpect(status().isOk());
        assertThat(events.count()).isEqualTo(1);
        verify(stream,times(1)).publish(anyList());
    }
    @Test void confirmedBusinessActionIsAtomicAndIgnoresInjectedUiFields() throws Exception {
        String code = "TEST-" + UUID.randomUUID();
        mvc.perform(post("/agentes/guardar").with(user("alice").roles("USER")).with(csrf())
                .param("codigo",code).param("hostname","test-host")
                .param("uiEventsPayload","[{\"key\":\"secret\"}]")
                .param("id","99999"))
                .andExpect(status().is3xxRedirection());
        assertThat(agents.findByCodigo(code)).isPresent();
        assertThat(events.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getSource()).isEqualTo(AuditTypes.Source.SERVER_CONFIRMED);
            assertThat(e.getPayload()).contains("AGENTE_GUARDADO").doesNotContain("secret");
        });
        verify(stream,times(1)).publish(anyList());
        events.deleteAll(); clearInvocations(stream);
        String rolledBack = "ROLLBACK-" + UUID.randomUUID();
        Agente agent = new Agente(); agent.setCodigo(rolledBack); agent.setHostname("rollback-host");
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            agentsService.guardarDesdeWeb(agent,"alice",UUID.randomUUID());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(agents.findByCodigo(rolledBack)).isEmpty();
        assertThat(events.count()).isZero();
        verify(stream,never()).publish(anyList());
    }
    @Test void multipleEventsUseJdbcBatchingAndCommitTogether() throws Exception {
        String ctx = context();
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        try {
            var bulk = new ArrayList<Map<String,Object>>();
            for (long i=1; i<=64; i++) bulk.add(event(i,"INPUT","codigo","bulk"));
            send(json.writeValueAsBytes(Map.of("contextId",ctx,"batchId",UUID.randomUUID(),"events",bulk)))
                    .andExpect(status().isOk()).andExpect(jsonPath("accepted").value(64));
            assertThat(stats.getEntityInsertCount()).isEqualTo(64);
            assertThat(stats.getPrepareStatementCount()).isLessThan(10);
            assertThat(events.count()).isEqualTo(64);
        } finally { stats.setStatisticsEnabled(false); }
    }
    @Test void agentAuditPageAndHistoryContainOnlyAssignedEvents() throws Exception {
        String code = "WEB-" + UUID.randomUUID();
        Agente agent = new Agente(); agent.setCodigo(code); agent.setHostname("web-test"); agents.save(agent);
        mvc.perform(get("/agentes/{codigo}/auditoria",code).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("auditoria"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-audit-dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-audit-agent=\""+code+"\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Asegúrate de que el agente Python esté ejecutándose"))));
        mvc.perform(get("/auditoria").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("audit-live"));
        String response = mvc.perform(post("/api/audit/context?page=agent-audit").param("agentCode",code)
                .session(session).with(user("alice").roles("USER")).with(csrf().asHeader())
                .header("Origin","http://localhost:8080"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String assigned = json.readTree(response).get("contextId").asText();
        send(batch(assigned,event(1,"INPUT","demo-notes","assigned text"))).andExpect(status().isOk());
        send(batch(context(),event(1,"INPUT","codigo","global only"))).andExpect(status().isOk());
        mvc.perform(get("/api/audit/history").param("agentCode",code).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentCode").value(code))
                .andExpect(jsonPath("$[0].event.value").value("assigned text"));
        mvc.perform(post("/api/audit/context?page=agent-audit").param("agentCode","unknown-agent")
                .session(session).with(user("alice").roles("USER")).with(csrf().asHeader())
                .header("Origin","http://localhost:8080")).andExpect(status().isNotFound());
        mvc.perform(post("/api/audit/context?page=agent-audit")
                .session(session).with(user("alice").roles("USER")).with(csrf().asHeader())
                .header("Origin","http://localhost:8080")).andExpect(status().isBadRequest());
    }
    @Test void legacyMachineEndpointRequiresDedicatedAuthentication() throws Exception {
        mvc.perform(post("/api/agentes/telemetria").param("codigo","test")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agentes/telemetria").with(httpBasic("admin","test-admin-password-123")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agentes/telemetria").with(httpBasic("agent","test-agent-password-123"))
                .param("codigo","MACHINE-TEST").param("hostname","test")).andExpect(status().isOk());
        assertThat(events.count()).isZero(); // Los datos legados no se promocionan a acciones confirmadas.
    }
}
