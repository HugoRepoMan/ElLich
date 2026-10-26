/*
 * CONTEXTO INICIAL
 * Verifica el recorrido completo usando Tomcat real en un puerto aleatorio y H2.
 * Un cliente HTTP inicia sesión, obtiene CSRF, guarda un agente de prueba y abre
 * su auditoría. Después crea una captura, envía un evento y comprueba su llegada
 * por SSE y su presencia en el historial filtrado. No simula pulsaciones físicas
 * en un navegador: prueba el contrato HTTP y la entrega del servidor.
 */
package org.example.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** HTTP real: sesión/login, CSRF de Thymeleaf, ingreso y entrega SSE tras commit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.audit.admin-password=http-test-password-123", "app.audit.agent-password="
})
class AuditHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)); }
    String extract(String html, String regex) {
        var matcher = Pattern.compile(regex).matcher(html);
        assertThat(matcher.find()).as("token CSRF presente").isTrue();
        return matcher.group(1);
    }
    @Test void receivesCommittedEventThroughAuthenticatedSse() throws Exception {
        var cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        String login = client.send(request("/login").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        String token = extract(login,"name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
        String form = "username=admin&password=http-test-password-123&_csrf=" + URLEncoder.encode(token,StandardCharsets.UTF_8);
        var signedIn = client.send(request("/login").header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(signedIn.statusCode()).isEqualTo(302);
        String page = client.send(request("/agentes").GET().build(),HttpResponse.BodyHandlers.ofString()).body();
        String csrf = extract(page,"name=\"_csrf\" content=\"([^\"]+)\"");
        String agentCode = "HTTP-SSE-" + UUID.randomUUID();
        var saved = client.send(request("/agentes/guardar").header("Content-Type","application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString("codigo="+agentCode+"&hostname=http-test")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(saved.statusCode()).isEqualTo(302);
        var auditPage = client.send(request("/agentes/"+agentCode+"/auditoria").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(auditPage.statusCode()).isEqualTo(200);
        assertThat(auditPage.body()).contains("data-audit-panel", "data-audit-dashboard", "data-audit-agent=\""+agentCode+"\"");
        var grant = client.send(request("/api/audit/context?page=agent-audit&agentCode="+agentCode).header("Origin","http://localhost:8080")
                .header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(grant.statusCode()).isEqualTo(200);
        String context = json.readTree(grant.body()).get("contextId").asText();
        var opened = client.send(request("/api/audit/stream?agentCode="+agentCode).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(opened.statusCode()).isEqualTo(200);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try (var input = opened.body(); var reader = new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            var received = executor.submit(() -> {
                String line;
                while ((line=reader.readLine()) != null) {
                    if (line.startsWith("data:") && line.contains(context)) return line.substring(5);
                }
                throw new EOFException("SSE terminado sin el evento");
            });
            String body = json.writeValueAsString(Map.of("contextId",context,"batchId",UUID.randomUUID(),"events",List.of(
                    new AuditTypes.Event(1,System.currentTimeMillis(),AuditTypes.Type.INPUT,"demo-notes",null,
                            "http-sse-test",0,13,13,false))));
            var ack = client.send(request("/api/audit/batch").header("Origin","http://localhost:8080")
                    .header("X-CSRF-TOKEN",csrf).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(ack.statusCode()).isEqualTo(200);
            var event = json.readTree(received.get(10,TimeUnit.SECONDS));
            assertThat(event.get("agentCode").asText()).isEqualTo(agentCode);
            assertThat(event.get("actor").asText()).isEqualTo("admin");
            assertThat(event.get("event").get("value").asText()).isEqualTo("http-sse-test");
            assertThat(event.get("source").asText()).isEqualTo("BROWSER_UNVERIFIED");
            var history = client.send(request("/api/audit/history?agentCode="+agentCode).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(history.body()).contains("http-sse-test");
        } finally { executor.shutdownNow(); }
    }
}
