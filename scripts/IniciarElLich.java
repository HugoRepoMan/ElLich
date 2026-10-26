/*
 * CONTEXTO INICIAL
 * Lanzador local de desarrollo utilizado por iniciar-ellich.cmd con Java 21.
 * Prepara las variables de entorno, genera una clave local si no se configuró una,
 * comprueba el puerto y localiza el JAR. Si faltan el ejecutable o sus fuentes cambiaron,
 * intenta compilar con Maven. Después inicia Spring Boot y conserva la consola activa.
 * La clave generada se guarda en target, que está ignorado por Git. PostgreSQL debe
 * estar disponible por separado; este lanzador no inicia la base de datos.
 */
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Lanzador local para Windows/JDK 21. No requiere ejecutar scripts de PowerShell. */
public class IniciarElLich {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Puerto invalido");
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("pom.xml")))
            throw new IllegalStateException("Ejecuta iniciar-ellich.cmd desde la carpeta del proyecto");
        Path target = root.resolve("target");
        Files.createDirectories(target);
        Map<String,String> environment = new HashMap<>(System.getenv());
        String password = environment.getOrDefault("AUDIT_ADMIN_PASSWORD", "");
        if (password.isBlank()) {
            Path keyFile = target.resolve(".local-admin-password");
            if (!Files.exists(keyFile)) {
                byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
                Files.writeString(keyFile, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), StandardOpenOption.CREATE_NEW);
            }
            password = Files.readString(keyFile).trim();
            System.out.println("Clave de administrador local: " + keyFile);
        }
        if (password.length() < 12) throw new IllegalArgumentException("AUDIT_ADMIN_PASSWORD debe contener al menos 12 caracteres");
        environment.put("AUDIT_ADMIN_PASSWORD", password);
        if (environment.getOrDefault("AUDIT_ORIGIN", "").isBlank())
            environment.put("AUDIT_ORIGIN", "http://localhost:" + port);
        String address = "http://localhost:" + port + "/agentes";
        boolean occupied;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            occupied = true;
        } catch (IOException unavailable) { occupied = false; }
        if (occupied) {
            System.out.println("El puerto ya esta ocupado. Comprueba: " + address);
            return;
        }
        Path jar = target.resolve("ElLich-1.0-SNAPSHOT.jar");
        boolean rebuild = !Files.isRegularFile(jar);
        if (!rebuild) {
            var built = Files.getLastModifiedTime(jar);
            rebuild = Files.getLastModifiedTime(root.resolve("pom.xml")).compareTo(built) > 0;
            if (!rebuild) try (var sources = Files.walk(root.resolve("src/main"))) {
                rebuild = sources.filter(Files::isRegularFile).anyMatch(file -> {
                    try { return Files.getLastModifiedTime(file).compareTo(built) > 0; }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                });
            }
        }
        if (rebuild) {
            System.out.println("Compilando con Maven; se necesita mvn en PATH y acceso a las dependencias.");
            var build = new ProcessBuilder("cmd.exe", "/d", "/c", "mvn -B -DskipTests package");
            build.directory(root.toFile()); build.environment().putAll(environment); build.inheritIO();
            if (build.start().waitFor() != 0) throw new IllegalStateException("Fallo Maven. Revisa el error anterior o compila desde IntelliJ.");
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--server.port=" + port);
        builder.directory(root.toFile()); builder.environment().putAll(environment); builder.inheritIO();
        System.out.println("Iniciando ElLich: " + address);
        System.out.println("Usuario: " + environment.getOrDefault("AUDIT_ADMIN_USER", "admin"));
        System.out.println("Espera a Started Main. Conserva esta ventana abierta; Ctrl+C detiene el servidor.");
        Process server = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::destroy));
        int exit = server.waitFor();
        if (exit != 0) throw new IllegalStateException("Spring Boot no pudo arrancar. Revisa el error anterior.");
    }
}
