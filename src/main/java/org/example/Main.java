/*
 * CONTEXTO INICIAL
 * Punto de entrada del monolito Spring Boot. Al ejecutar main, Spring crea el servidor
 * HTTP, conecta JPA con PostgreSQL y descubre los componentes dentro de org.example.
 * Los controladores atienden peticiones, los servicios aplican las reglas y los repositorios
 * acceden a las tablas. Las vistas HTML se construyen con Thymeleaf.
 */
package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
