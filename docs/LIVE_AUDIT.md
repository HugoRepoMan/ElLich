# Auditoría en vivo de ElLich

La ruta /agentes/{codigo}/auditoria (botón Ver Actividad) contiene captura, reconstrucción y feed web del agente. /auditoria y /admin/auditoria-viva muestran la auditoría general.

El módulo está integrado en Spring Boot 3.4.3, Java 21, MVC, Thymeleaf y JPA. Conserva las tablas legadas de agentes y añade audit_events. La captura web está desactivada hasta pulsar **Activar captura** en /agentes. Sólo registra los elementos autorizados de esa página; el login y el dashboard no cargan el capturador.

## Inicio local en Windows

Ejecuta desde la carpeta del proyecto:

~~~powershell
.\iniciar-ellich.cmd
~~~

El lanzador usa Java 21, inicia el servidor en http://localhost:8080/agentes y mantiene la consola abierta. Cuando aparezca Started Main, abre la página. Si no había una clave de administrador configurada, genera una clave aleatoria y la guarda en target/.local-admin-password (ignorado por Git); usuario admin. Si ya definiste AUDIT_ADMIN_PASSWORD, utiliza esa variable. No cambia la política de ejecución de PowerShell.

El ejecutable local preparado durante esta sesión utiliza las dependencias guardadas en target/audit-m2: conserva esa carpeta. Si no existe el JAR o cambian las fuentes, el lanzador intenta reconstruirlo con Maven, que debe estar en PATH y tener acceso a sus dependencias. También puedes compilar desde IntelliJ. Conserva PostgreSQL en ejecución y configura DB_* si sus credenciales difieren de las del proyecto.

## Ejecutar

Configura PostgreSQL igual que antes y define las credenciales, sin guardarlas en Git:

~~~powershell
$env:AUDIT_ADMIN_PASSWORD="elige-una-clave-larga-y-unica"
# Opcional: cuenta operator, puede operar agentes pero no leer auditoría.
$env:AUDIT_OPERATOR_PASSWORD="otra-clave-larga-y-unica"
$env:AUDIT_ORIGIN="http://localhost:8080"
mvn spring-boot:run
~~~

Cada clave configurada debe tener al menos 12 caracteres. El usuario administrador es admin, configurable con AUDIT_ADMIN_USER. No hay contraseña por defecto. El almacén de usuarios en memoria es una integración inicial; para desplegar, conectar el proveedor de identidad de la organización.

1. Inicia sesión en http://localhost:8080/agentes.
2. Abre http://localhost:8080/admin/auditoria-viva en otra pestaña como administrador.
3. En agentes, selecciona el agente al que asignar la captura y pulsa Activar captura. Si no existe, guarda primero su registro. También puedes abrir Ver Actividad y activar la captura directamente en esa pantalla; el agente se asigna automáticamente. Escribe texto de prueba en Código, Hostname, Sistema o Notas de prueba; prueba Shift, Ctrl, AltGr, selección, Backspace, flechas, Enter en Notas, pegar y deshacer.
4. Prueba las selecciones y los radios. El campo de contraseña de prueba sólo genera foco y salida con máscara fija: nunca transmite sus caracteres, su longitud o sus teclas.
5. Guarda un agente: verás un CLICK declarado por el navegador y una ACTION/AGENTE_GUARDADO confirmada por el servidor. El guardado utiliza AJAX para actualizar la tabla sin desmontar la cola de captura.
6. Pulsa Detener y enviar antes de cerrar una captura. Una navegación abrupta puede perder eventos aún en memoria; keepalive es un esfuerzo de entrega, no una garantía.

El agente Python existente usa ahora POST /api/agentes/telemetria con HTTP Basic y el usuario agent. Configura AUDIT_AGENT_PASSWORD en servidor y cliente para habilitarlo. Esta ruta no acepta una sesión del navegador ni el rol ADMIN como credencial de máquina. Su protocolo y sus eventos legados siguen separados del nuevo AuditEvent; no se consideran evidencia de acciones confirmadas. El filtrado de contraseñas del DOM **no protege las capturas globales existentes del agente Python**.

## Archivos y responsabilidades

| Archivo | Responsabilidad y decisión de seguridad |
| --- | --- |
| audit/AuditSecurityConfig.java | Login, roles USER/ADMIN/AGENT, CSRF web y separación de API de máquinas. |
| audit/AuditIngressFilter.java | Origen exacto configurado y límite de 64 KiB incluso para cuerpos chunked. |
| audit/AuditPolicy.java | Lista de campos y tipos por página; redacción de secretos en el servidor. |
| audit/AuditTypes.java | DTOs de tamaño y tipos acotados. No admite actor, fuente o prioridad enviados por cliente. |
| audit/AuditService.java | Contexto ligado a sesión/actor, expiración, secuencias, rate limit y reintentos idempotentes. |
| audit/AuditPersistence.java | Lote JPA en una transacción y acciones de negocio con auditoría atómica. |
| audit/AuditStream.java | SSE con escritores aislados, colas limitadas, prioridad y reenvío temporal. |
| entity/AuditEvent.java | Entidad sin relaciones/cascadas, secuencia con allocationSize=50 e índice de lectura. |
| repository/AuditEventRepository.java | Consulta de últimos 200 eventos sin cargar todo el historial. |
| controller/AuditController.java | Endpoints con validación estricta de JSON y respuestas sin valores sensibles. |
| static/js/audit-spy.js | Delegación, captura activable, exclusión de contraseñas y lotes con reintentos. |
| static/js/audit-replay.js | Buffer provisional de teclas reconciliado por snapshots de input/foco. |
| static/js/audit-dashboard.js | Feed y reconstrucción por contexto; textContent evita ejecutar HTML capturado. |
| templates/audit-live.html | Dashboard restringido a administradores. |
| static/css/audit-live.css | Colores de teclado, clic, interfaz y acciones confirmadas. |

Las rutas de recursos parten de src/main/resources y las clases de src/main/java/org/example. Las modificaciones de AgenteController, AgenteService y lista.html conectan el módulo con el flujo existente.

## Contrato HTTP

Todas las rutas /api/audit requieren sesión autenticada. Las operaciones de escritura requieren Origin igual a AUDIT_ORIGIN y el header CSRF obtenido de las etiquetas meta de la página. El servidor rechaza propiedades JSON desconocidas.

| Método y ruta | Uso |
| --- | --- |
| POST /api/audit/context?page=agents&agentCode=CODIGO | Crea contexto aleatorio ligado a un agente existente y devuelve campos autorizados. page=agent-audit habilita la captura en su pantalla de auditoría. |
| POST /api/audit/batch | Recibe contextId, batchId y hasta 64 events. Responde sólo después del commit. |
| DELETE /api/audit/context/{id} | Revoca un contexto de la sesión actual. |
| GET /api/audit/history?agentCode=CODIGO | Últimos 200 registros del agente; sin filtro devuelve historial general. ADMIN. |
| GET /api/audit/stream?agentCode=CODIGO | SSE filtrado por agente en el servidor; sin filtro es general. ADMIN. |
| GET /admin/auditoria-viva | Dashboard; ADMIN. |

Cada evento incluye seq, clientTime, type, elementId, key, value, modifiers, selectionStart, selectionEnd y repeat. Los modificadores son una máscara: Shift=1, Ctrl=2, Alt=4, Meta=8, AltGr=16. El servidor asigna actor, receivedAt, contexto, gapsBefore y fuente. clientTime es una declaración no confiable, nunca la base del orden ni de la identidad.

## Integridad y límites de confianza

**Ningún JavaScript controlado por el usuario puede probar que un clic o una tecla ocurrieron físicamente.** Un HMAC con una clave entregada al mismo navegador tampoco lo prueba: el usuario puede firmar eventos fabricados. isTrusted sólo descarta eventos sintéticos corrientes dentro de la página; no es una frontera de seguridad.

La validación implementada autentica quién envía, liga el lote a una sesión y una página, restringe destinos/tipos, impide promocionar datos a SERVER_CONFIRMED y rechaza secuencias repetidas. La huella SHA-256 del cuerpo sólo detecta un reintento con el mismo batchId y distinto contenido. No es firma, cifrado ni prueba de veracidad.

BROWSER_UNVERIFIED significa telemetría declarada, aunque pase todos los controles. SERVER_CONFIRMED significa que una operación autorizada del backend se confirmó en base de datos. No prueba que el usuario hiciera un clic: podría haber enviado directamente una petición válida. Para añadir Eliminar u otra operación, instrumentar su servicio transaccional y registrar la acción después de aplicar autorización y validaciones; nunca aceptar su confirmación desde el endpoint de telemetría.

El registro confirmado de guardado participa en la misma transacción que el agente y se publica en SSE mediante afterCommit. Si el guardado o la auditoría fallan, ambos hacen rollback. No se persisten JSESSIONID ni tokens CSRF: se usa una referencia aleatoria independiente.

La aplicación no actualiza registros AuditEvent. Un propietario de base de datos todavía podría alterarlos. Para requisitos de evidencia resistente a administradores, hacen falta permisos separados, almacenamiento externo y/o un registro firmado con claves fuera de la aplicación; este módulo no promete esa propiedad.

## Privacidad y reconstrucción

La política se configura en el servidor, no mediante selectores enviados por el usuario. Sólo se captura texto en los campos explícitos del formulario de prueba. Para ampliar la lista, agregar el campo a AuditPolicy y su data-audit-id al HTML, revisando primero qué contenido podría contener. data-audit-private excluye contenedores completos en el cliente.

Las contraseñas y autocomplete sensible se excluyen antes de leer value; el campo demo-secret también se vuelve [REDACTED] en el servidor, aun si un cliente modificado envía texto. Los eventos de clic no conservan valores arbitrarios. No introducir tokens o información personal en los campos de texto autorizados: por diseño sus valores sí se almacenan.

keydown ofrece una reconstrucción provisional. Los snapshots INPUT y FOCUS/BLUR incluyen valor y selección, y corrigen pegado, IME, borrado, autocompletado y deshacer. ArrowUp/Down, selección compleja y atajos dependientes del sistema se resuelven con el snapshot de selección/input posterior. No es reproducción pixel a pixel del DOM ni navegación entre páginas. El texto se limita a 512 unidades UTF-16 por campo/evento. Se indica cuando falta un snapshot inicial o se perdieron eventos.

## Batching y backpressure

- Cliente: vaciado cada 400 ms, hasta 64 eventos y 60 KB codificados por lote; cola de 512 eventos más un único lote en vuelo. Reintenta el cuerpo idéntico con backoff y jitter ante errores de red, HTTP 429 o 5xx. No guarda texto en localStorage.
- Base de datos: una transacción por lote; persist y flush con hibernate.jdbc.batch_size=50 y order_inserts=true. La secuencia de AuditEvent permite batching; IDENTITY lo deshabilitaría. El resto de entidades conserva su estrategia previa.
- Ingreso: máximo ocho lotes procesándose simultáneamente. Diez lotes por segundo por contexto, con ráfaga de veinte; hasta cuatro capturas activas por sesión, concesión de dos minutos sin actividad/heartbeat y vida máxima de treinta minutos. El cliente renueva la concesión cada veinte segundos y los lotes también la renuevan. Al saturarse, devuelve 429 para reintentar.
- SSE: hasta dieciséis observadores y dos por usuario. Cada observador tiene una cola de 256 entradas; el anillo de reconexión retiene 4096 eventos en memoria. Las escrituras usan hilos virtuales separados del ingreso y del commit. Se envían heartbeats y la conexión se renueva cada minuto; se comprueba que la sesión siga teniendo ADMIN.
- Al llenar una cola, se descartan primero eventos de menor prioridad: KEY_UP/foco antes de teclas, snapshots/clics y confirmaciones del servidor. La prioridad decide qué conservar; la entrega conserva el orden de publicación. gapsBefore revela huecos de secuencia de ingreso y gap revela pérdidas en SSE. Una cola llena sólo de confirmaciones también puede perder entradas en vivo; PostgreSQL sigue siendo el registro durable.

No hay entrega exactamente una vez entre navegador y servidor. La idempotencia de reintentos cubre el último lote del contexto en una instancia viva. Un reinicio pierde contextos y el anillo SSE, pero conserva los eventos comprometidos en PostgreSQL. Las restricciones únicas de contexto/secuencia impiden duplicarlos. Si el contexto expira o desaparece, el cliente informa cuántos eventos quedaron sin confirmar y exige iniciar una nueva captura.

El dashboard carga los últimos 200 registros y deduplica la superposición con SSE; conserva hasta 300 filas, cincuenta contextos y dos mil IDs para acotar memoria. Después de una pérdida intenta recuperar historial, pero una sesión extensa o una caída larga puede exceder esa ventana. No afirma reconstrucción completa en ese caso.

Al abandonar una página, el cliente deja de admitir eventos y trata de drenar y cerrar su contexto con keepalive. Si el navegador no entrega el cierre, el cupo queda disponible en un máximo de dos minutos sin heartbeat. No se elimina un contexto que sigue vivo para admitir otra pestaña. Si hay cuatro capturas realmente activas, activar una quinta devuelve 429 con una explicación.

Esta implementación está pensada para **una instancia del monolito**. Para varias réplicas, usar sesiones compartidas, un outbox transaccional y un broker compartido. PostgreSQL confirma los registros antes de publicar; una caída justo entre commit y publicación puede omitir un evento en vivo, recuperable mediante historial. El replay en memoria no sustituye un outbox.

## Despliegue y esquema

En desarrollo se mantiene ddl-auto=update, como en el proyecto original. En despliegues administrados, aplicar docs/sql/audit_events.sql mediante el propietario de esquema y cambiar Hibernate a validate. El script sólo crea la nueva tabla/secuencia: las tablas previas deben existir.

Usar HTTPS, AUDIT_ORIGIN con el origen externo exacto (sin barra final ni path) y COOKIE_SECURE=true. Si cambia el puerto u origen, actualizar AUDIT_ORIGIN. El proxy debe desactivar buffering para /api/audit/stream y admitir una conexión de al menos sesenta segundos. No habilitar CORS con credenciales para orígenes arbitrarios.

Usar un rol de ejecución con SELECT/INSERT sobre audit_events y USAGE/SELECT sobre audit_event_seq, sin UPDATE/DELETE de auditoría. Las migraciones/retención corresponden a otro rol. Definir retención y acceso acordes al propósito de los datos; la aplicación no elimina registros automáticamente.

## Pruebas

~~~powershell
mvn test
node --test src/test/js/audit-replay.test.mjs src/test/js/audit-spy.test.mjs
~~~

La suite Java prueba autenticación/roles, CSRF, Origin, límites de tamaño, contexto, secuencia, redacción en servidor, rechazo de fuentes falsas, ACK idempotente, reintento tras fallo de persistencia, rollback conjunto, JDBC batching y admisión por prioridad. Una prueba adicional levanta Tomcat en un puerto aleatorio e inicia sesión por HTTP para validar CSRF y la recepción SSE de un evento comprometido. Usa H2 en modo PostgreSQL para no depender de la base del usuario. Las pruebas JavaScript verifican selección, modificadores, Unicode, snapshots e IME. H2 no sustituye una prueba de carga o validación contra PostgreSQL real.

Referencias oficiales: [SSE en Spring MVC](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html), [CSRF de Spring Security](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [batching e IDENTITY en Hibernate](https://docs.jboss.org/hibernate/orm/6.5/userguide/html_single/Hibernate_User_Guide.html#batch).

### Validación realizada el 25 de octubre de 2026

Las veinte fuentes Java (aplicación y pruebas) compilaron con el compilador del JDK 21. Pasaron once pruebas JUnit y cuatro pruebas de reconstrucción JavaScript. Los tres scripts del cliente también pasaron la comprobación de sintaxis de Node. La integración usó H2 en modo PostgreSQL y una conexión HTTP/SSE real a Tomcat.

En este entorno Windows restringido, Maven falló con AccessDeniedException al cerrar archivos ZIP/JAR del classpath mediante javac. Para ejecutar la verificación se compiló con JavaCompiler y se ejecutó la suite con JUnit Platform directamente, con las mismas dependencias del proyecto. No se afirma que el comando mvn test haya pasado en este entorno. Docker denegó acceso a su servicio, por lo que no se ejecutó una instancia de PostgreSQL real ni una prueba de carga.

### Arranque local comprobado posteriormente

Se comprobó una conexión JDBC a PostgreSQL 16.15 ya disponible en localhost:5432 y se arrancó la aplicación en localhost:8080. El inicio de sesión de administrador funcionó y devolvieron HTTP 200 las páginas /agentes y /admin/auditoria-viva, el historial y los archivos JavaScript/CSS. Esta comprobación de arranque y lectura no sustituye una prueba de carga o de interacción física en el navegador. El registro de arranque está en target/ellich-server.log.

### Integración de Ver Actividad con la auditoría web

Cada nueva captura de la interfaz se asocia a un agente seleccionado y existente. El servidor guarda agent_code desde el contexto, nunca desde un evento enviado en el cuerpo del lote. El historial y el SSE de /agentes/{codigo}/auditoria sólo muestran ese agente. El guardado confirmado de un agente también incorpora su código.

Los registros web anteriores cuyo agent_code es NULL se conservan en /auditoria. No se asignan retroactivamente a una máquina, porque no existe evidencia para determinar a qué agente correspondían. La asignación elegida en la interfaz tampoco prueba la identidad física del equipo del navegador: sigue siendo telemetría declarada.

La página de cada agente permite Activar captura y escribir en su área de prueba sin depender del proceso Python. El historial Python permanece en una sección desplegable independiente y no bloquea ni sustituye el feed web.

Las pruebas adicionales verifican el renderizado de esa ruta, la asociación del contexto, la redacción de contraseñas sin leer su valor, los lotes del capturador y el filtrado de historial/SSE. La herramienta de navegador no estaba disponible en esta sesión; se utilizó HTTP real y un entorno DOM simulado para la lógica del cliente.

Validación de esta corrección: trece pruebas Java y ocho JavaScript exitosas. El recorrido HTTP crea un agente de prueba en H2, renderiza su página de auditoría, crea un contexto asociado, envía un evento y verifica su recepción SSE y su historial filtrado. La ruta de un agente ya existente en PostgreSQL y /auditoria respondieron HTTP 200 con las plantillas actualizadas.

Corrección del límite de capturas: se añadió caducidad por inactividad de dos minutos, heartbeat cada veinte segundos, cierre con keepalive y mensajes de error controlados en JSON. Las pruebas de reloj simulado comprueban que los contextos abandonados liberan cupos, los activos se conservan y sigue existiendo el límite de cuatro capturas simultáneas y treinta minutos de vida máxima.
