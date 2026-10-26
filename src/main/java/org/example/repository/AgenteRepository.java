/*
 * CONTEXTO INICIAL
 * Abstrae el acceso JPA a la tabla agentes. Spring genera las operaciones comunes
 * de lectura y escritura y la búsqueda findByCodigo. AgenteService usa esa búsqueda
 * para decidir si corresponde insertar un equipo o actualizar el que ya existe.
 * El repositorio accede a datos; las reglas de negocio y las transacciones viven en servicios.
 */
package org.example.repository;

import org.example.entity.Agente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgenteRepository extends JpaRepository<Agente, Long> {

    Optional<Agente> findByCodigo(String codigo);
}
