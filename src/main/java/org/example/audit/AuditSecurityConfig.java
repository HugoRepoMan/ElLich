/*
 * CONTEXTO INICIAL
 * Configura quién puede usar la aplicación y cómo se autentica cada canal.
 * El navegador utiliza inicio de sesión, cookies y CSRF; ADMIN puede consultar auditoría
 * y USER puede operar la interfaz. La API del agente Python usa Basic y el rol AGENT
 * en una cadena sin sesión de navegador. Las claves proceden de variables de entorno
 * y se codifican con BCrypt. El almacén de usuarios actual es de memoria.
 */
package org.example.audit;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class AuditSecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    /** Sin credenciales por defecto. Sustituir por el proveedor de identidad en producción. */
    @Bean UserDetailsService users(PasswordEncoder encoder,
            @Value("${app.audit.admin-user:admin}") String admin,
            @Value("${app.audit.admin-password:}") String password,
            @Value("${app.audit.operator-password:}") String operatorPassword,
            @Value("${app.audit.agent-password:}") String agentPassword) {
        if (password.isBlank() || password.length() < 12)
            throw new IllegalStateException("Configurar AUDIT_ADMIN_PASSWORD con al menos 12 caracteres");
        var users = new InMemoryUserDetailsManager();
        users.createUser(User.withUsername(admin).password(encoder.encode(password)).roles("ADMIN", "USER").build());
        if (!operatorPassword.isBlank()) {
            if (operatorPassword.length() < 12) throw new IllegalStateException("AUDIT_OPERATOR_PASSWORD demasiado corta");
            users.createUser(User.withUsername("operator").password(encoder.encode(operatorPassword)).roles("USER").build());
        }
        if (!agentPassword.isBlank()) {
            if (agentPassword.length() < 12) throw new IllegalStateException("AUDIT_AGENT_PASSWORD demasiado corta");
            users.createUser(User.withUsername("agent").password(encoder.encode(agentPassword)).roles("AGENT").build());
        }
        return users;
    }
    /** API de máquinas: Basic sin cookies ni sesión. Usar HTTPS fuera de localhost. */
    @Bean @Order(1) SecurityFilterChain machine(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/agentes/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("AGENT"))
                .httpBasic(withDefaults()).build();
    }
    @Bean @Order(2) SecurityFilterChain browser(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/login", "/error", "/css/**", "/js/**").permitAll()
                .requestMatchers("/admin/**", "/auditoria", "/api/audit/history", "/api/audit/stream",
                        "/agentes/*/auditoria").hasRole("ADMIN")
                .anyRequest().hasAnyRole("USER", "ADMIN"))
                .formLogin(withDefaults())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                // CSRF activo para JSON. Las API responden 401, sin redirigir a HTML.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        (req, res, ex) -> res.sendError(401),
                        req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/audit/")))
                .build();
    }
}
