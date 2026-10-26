/*
 * CONTEXTO INICIAL
 * Protege la entrada HTTP de /api/audit antes de que el controlador lea el JSON.
 * En las escrituras exige el origen configurado y limita el cuerpo real a 64 KiB,
 * incluso cuando llega por partes. El cuerpo validado se vuelve a ofrecer al controlador.
 * También evita el almacenamiento de estas respuestas en caché. La autenticación y
 * el token CSRF los comprueba Spring Security; este filtro añade controles de transporte.
 */
package org.example.audit;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Límite real de cuerpo, incluso con chunked, antes de deserializar JSON. */
@Component
public class AuditIngressFilter extends OncePerRequestFilter {
    private final String origin;
    public AuditIngressFilter(@Value("${app.audit.origin:http://localhost:8080}") String origin) { this.origin = origin; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/audit/");
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        res.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
            // No confiar en Host o X-Forwarded-* proporcionados por el cliente.
            if (!origin.equals(req.getHeader("Origin"))) { res.sendError(403, "Origen no autorizado"); return; }
            byte[] body = req.getInputStream().readNBytes(65_537);
            if (body.length > 65_536) { res.sendError(413, "Máximo 64 KiB"); return; }
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    };
                }
                @Override public BufferedReader getReader() {
                    return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }, res);
        } else chain.doFilter(req, res);
    }
}
