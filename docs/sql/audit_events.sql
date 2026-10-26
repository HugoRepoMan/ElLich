-- CONTEXTO INICIAL
-- Esquema PostgreSQL del historial web para instalaciones gestionadas mediante SQL.
-- Crea la tabla, los índices y una secuencia cuyo incremento coincide con el bloque
-- JPA de 50 identificadores. La parte final incorpora agent_code en instalaciones
-- previas. Se ejecuta con el propietario del esquema; los GRANT de ejemplo describen
-- los permisos mínimos del rol de ejecución y no se aplican automáticamente.
-- Ejecutar como propietario de esquema antes de ddl-auto=validate.
-- La secuencia incrementa 50 para coincidir con allocationSize=50 de Hibernate.
CREATE SEQUENCE IF NOT EXISTS audit_event_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT PRIMARY KEY,
    context_id UUID NOT NULL,
    client_sequence BIGINT,
    actor VARCHAR(100) NOT NULL,
    page VARCHAR(32) NOT NULL,
    agent_code VARCHAR(255),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    source VARCHAR(24) NOT NULL,
    payload TEXT NOT NULL,
    gaps_before BIGINT NOT NULL,
    CONSTRAINT uq_audit_context_seq UNIQUE (context_id, client_sequence)
);
CREATE INDEX IF NOT EXISTS idx_audit_received ON audit_events (received_at);
-- Ejemplo para el rol de ejecución ya creado (ajustar el nombre antes de ejecutar):
-- GRANT SELECT, INSERT ON audit_events TO ellich_app;
-- GRANT USAGE, SELECT ON SEQUENCE audit_event_seq TO ellich_app;
-- REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM ellich_app;

-- Actualización para instalaciones que ya tenían audit_events:
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS agent_code VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_audit_agent_received ON audit_events (agent_code, received_at);
