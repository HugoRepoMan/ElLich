package org.example.repository;

import org.example.entity.EndpointEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EndpointEventRepository extends JpaRepository<EndpointEvent, Long> {

    /**
     * Recupera los eventos más recientes de un agente específico.
     * Usa paginación para evitar cargar todo el historial en memoria.
     *
     * @param codigo El código único del agente
     * @param pageable Parámetro de paginación (ej. PageRequest.of(0, 50))
     * @return Lista de eventos ordenados por timestamp descendente
     */
    @Query("""
        SELECT e FROM EndpointEvent e 
        JOIN e.agente a 
        WHERE a.codigo = :codigo 
        ORDER BY e.occurredAtMs DESC
        """)
    List<EndpointEvent> findRecentEventsByAgentCode(@Param("codigo") String codigo, Pageable pageable);

    /**
     * Elimina eventos antiguos para mantener la base de datos ligera.
     * Útil para ejecutar tareas de limpieza por cron.
     *
     * @param cutoffMs Timestamp en milisegundos (todo evento anterior a este se elimina)
     * @return Número de registros eliminados
     */
    @Query("DELETE FROM EndpointEvent e WHERE e.occurredAtMs < :cutoffMs")
    int deleteOldEvents(@Param("cutoffMs") Long cutoffMs);
}