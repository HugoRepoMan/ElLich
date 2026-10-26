/*
 * CONTEXTO INICIAL
 * Consulta el historial web de audit_events con Spring Data JPA.
 * Las búsquedas entregan como máximo 200 filas, ordenadas de más reciente a más antigua,
 * para el dashboard general o para un agente concreto. La escritura por lotes se realiza
 * en AuditPersistence mediante EntityManager para controlar la transacción y el flush.
 */
package org.example.repository;

import org.example.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Lectura limitada. No se usa save() por tecla. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop200ByOrderByReceivedAtDescIdDesc();
    List<AuditEvent> findTop200ByAgentCodeOrderByReceivedAtDescIdDesc(String agentCode);
}
