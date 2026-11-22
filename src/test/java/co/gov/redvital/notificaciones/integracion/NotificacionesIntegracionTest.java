package co.gov.redvital.notificaciones.integracion;

import static org.assertj.core.api.Assertions.assertThat;

import co.gov.redvital.notificaciones.aplicacion.NotificacionPersistenciaPort;
import co.gov.redvital.notificaciones.aplicacion.RegistroIdempotencia;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * El trabajador de punta a punta contra Kafka real (Testcontainers), un
 * servidor SMTP en memoria (GreenMail) y un sustituto HTTP de Identidad y
 * Donación. Los temas se crean de antemano y el broker no crea ninguno, como
 * en QA: si el trabajador usara un tema que el catálogo no tiene, fallaría aquí.
 */
@SpringBootTest
class NotificacionesIntegracionTest {

    private static final String TEMA = "redvital.donacion.notice-requested.v1";
    private static final String DLT = TEMA + ".notificaciones.dlt";
    private static final String SITIO = "https://qa.redvital.test";

    private static final String CLAVE_PROPIETARIO = "propietario-prueba";
    private static final String CLAVE_SERVICIO = "servicio-prueba";

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16").withDatabaseName("db_notifications");
    static final int PUERTO_SMTP = puertoLibre();
    static volatile GreenMail smtp;

    static {
        KAFKA.start();
        POSTGRES.start();
        prepararBase();
        crearTemas();
        smtp = iniciarSmtp();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.security.protocol", () -> "PLAINTEXT");
        // Como en el despliegue: el rol de servicio, con las migraciones reales. Aquí sí se
        // valida el mapeo contra el esquema, que en ejecución no se exige para arrancar.
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "notifications_servicio");
        r.add("spring.datasource.password", () -> CLAVE_SERVICIO);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> "true");
        r.add("redvital.sitio", () -> SITIO);
        r.add("redvital.correo.host", () -> "127.0.0.1");
        r.add("redvital.correo.puerto", () -> PUERTO_SMTP);
        r.add("redvital.correo.usuario", () -> "redvital.qa@correo.co");
        r.add("redvital.correo.clave", () -> "abcd efgh ijkl mnop"); // con espacios, como la muestra Gmail
        r.add("redvital.correo.starttls", () -> "false");
        // Esperas cortas con la misma forma que las de QA (de 1 a 20 veces la primera).
        r.add("redvital.reintentos.espera-inicial", () -> "100");
        r.add("redvital.reintentos.espera-maxima", () -> "2000");
        r.add("redvital.reintentos.espera-canal", () -> "300ms");
        r.add("redvital.reintentos.espera-canal-maxima", () -> "1s");
        r.add("server.port", () -> "0");
    }

    @Autowired
    NotificacionPersistenciaPort persistencia;

    @Autowired
    MeterRegistry metricas;

    @AfterAll
    static void detener() {
        smtp.stop();
    }

    @Test
    void el_rol_de_servicio_guarda_en_la_bandeja_de_idempotencia() throws Exception {
        String clave = "prueba-" + UUID.randomUUID();

        persistencia.guardar(new RegistroIdempotencia(clave, "notice.requested", null, Map.of("resultado", "entregado")));

        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notifications_servicio", CLAVE_SERVICIO);
             var consulta = conexion.prepareStatement(
                     "SELECT resultado ->> 'resultado' FROM notifications.bandeja_idempotencia WHERE clave_idempotencia = ?")) {
            consulta.setString(1, clave);
            try (var filas = consulta.executeQuery()) {
                assertThat(filas.next()).isTrue();
                assertThat(filas.getString(1)).isEqualTo("entregado");
            }
        }
    }

    @Test
    void confirma_la_cuenta_por_correo_con_el_enlace_al_sitio() throws Exception {
        UUID donante = UUID.randomUUID();
        String correo = "laura." + donante.toString().substring(0, 8) + "@correo.co";
        UUID aviso = publicar(donante, "cuenta_creada", "{}", correo, "Laura Pérez");

        MimeMessage mensaje = esperarCorreo(correo);
        assertThat(mensaje.getSubject()).isEqualTo("Tu cuenta de donante en RedVital está lista");
        assertThat(mensaje.getMessageID()).isEqualTo("<aviso-" + aviso + "@redvital>");
        assertThat(mensaje.getFrom()[0].toString()).contains("RedVital").contains("redvital.qa@correo.co");
        assertThat(mensaje.getHeader("Auto-Submitted")[0]).isEqualTo("auto-generated");
        String contenido = new String(mensaje.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String crudo = new String(mensaje.getRawInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(contenido + crudo).contains("ingresar");
        assertThat(contador("cuenta_creada", "entregado")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sin_correo_o_con_un_dominio_reservado_no_se_envia_nada_y_no_es_un_fallo() throws Exception {
        int antes = smtp.getReceivedMessages().length;
        UUID avisoSinContacto = publicar(UUID.randomUUID(), "cuenta_creada", "{}", null, null);
        UUID avisoReservado = publicar(UUID.randomUUID(), "cuenta_creada", "{}", "donante.1@redvital.test", "N");

        esperar(() -> contador("cuenta_creada", "destinatario_sin_contacto") >= 1
                && contador("cuenta_creada", "dominio_reservado") >= 1);
        assertThat(smtp.getReceivedMessages()).hasSize(antes);
        assertThat(llega(DLT, avisoSinContacto, Duration.ofSeconds(2))).isFalse();
        assertThat(llega(DLT, avisoReservado, Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void un_aviso_con_un_correo_mal_escrito_va_directo_a_mensajes_muertos() {
        UUID aviso = publicar(UUID.randomUUID(), "cuenta_creada", "{}", "esto no es un correo", "N");
        ConsumerRecord<String, String> muerto = esperarEn(DLT, aviso);
        assertThat(cabecera(muerto, "aviso-resultado")).isEqualTo("evento_mal_formado");
        assertThat(llega(TEMA + ".notificaciones.retry-0", aviso, Duration.ofSeconds(2))).as("sin reintentos").isFalse();
    }

    @Test
    void un_mensaje_mal_formado_va_directo_a_mensajes_muertos() {
        UUID aviso = UUID.randomUUID();
        enviar(aviso, "clave", "{esto no es json");
        assertThat(cabecera(esperarEn(DLT, aviso), "aviso-resultado")).isEqualTo("evento_mal_formado");
    }

    @Test
    void un_destinatario_rechazado_recorre_los_temas_de_reintento_y_termina_en_mensajes_muertos() {
        // Pasa la forma del contrato, pero el canal la rechaza: dos puntos seguidos.
        UUID aviso = publicar(UUID.randomUUID(), "cuenta_creada", "{}", "ana..b@correo.co", "N");

        ConsumerRecord<String, String> muerto = esperarEn(DLT, aviso);
        assertThat(cabecera(muerto, "aviso-resultado")).isEqualTo("destinatario_rechazado");
        // Ocho intentos: el original y siete reintentos por los temas del catálogo.
        for (int n = 0; n <= 5; n++) {
            assertThat(llega(TEMA + ".notificaciones.retry-" + n, aviso, Duration.ofSeconds(1))).as("retry-" + n).isTrue();
        }
        assertThat(contador("cuenta_creada", "destinatario_rechazado")).isEqualTo(8);
    }

    @Test
    void con_el_correo_caido_el_aviso_espera_y_se_entrega_cuando_vuelve() throws Exception {
        UUID donante = UUID.randomUUID();
        String correo = "espera." + donante.toString().substring(0, 8) + "@correo.co";
        smtp.stop();
        try {
            UUID aviso = publicar(donante, "cuenta_creada", "{}", correo, "Ana");
            esperar(() -> contador("cuenta_creada", "canal_no_disponible") >= 2);
            assertThat(llega(TEMA + ".notificaciones.retry-0", aviso, Duration.ofSeconds(1))).isFalse();
            assertThat(llega(DLT, aviso, Duration.ofSeconds(1))).isFalse();
        } finally {
            smtp = iniciarSmtp();
        }
        assertThat(esperarCorreo(correo).getSubject()).contains("RedVital");
    }

    @Test
    void el_aviso_de_campania_lleva_la_jornada() throws Exception {
        UUID donante = UUID.randomUUID();
        String correo = "campania." + donante.toString().substring(0, 8) + "@correo.co";
        publicar(donante, "campania_publicada", "{\"nombre\":\"Jornada Parque Explora\",\"sede\":\"Plazoleta\","
                + "\"inicia_en\":\"2026-10-28T13:00:00Z\",\"termina_en\":\"2026-10-29T22:00:00Z\"}", correo, "Ana");
        assertThat(esperarCorreo(correo).getSubject()).isEqualTo("Nueva jornada de donación: Jornada Parque Explora");
    }

    // ------------------------------------------------------------------ utilidades

    private double contador(String tipo, String resultado) {
        Counter c = metricas.find("redvital_avisos").tag("tipo", tipo).tag("resultado", resultado).counter();
        return c == null ? 0 : c.count();
    }

    /** EV-02 como lo publica Donación: con el correo y el nombre del destinatario. */
    private static UUID publicar(UUID donante, String tipo, String plantilla, String correo, String nombre) {
        UUID aviso = UUID.randomUUID();
        enviar(aviso, donante.toString(), "{\"aviso_id\":\"" + aviso + "\",\"tipo_aviso\":\"" + tipo
                + "\",\"destinatario_tipo\":\"donante\",\"destinatario_id\":\"" + donante
                + "\",\"correo\":" + (correo == null ? "null" : "\"" + correo + "\"")
                + ",\"nombre\":" + (nombre == null ? "null" : "\"" + nombre + "\"")
                + ",\"campania_id\":null,\"plantilla\":" + plantilla + ",\"generado_en\":\"" + Instant.now() + "\"}");
        return aviso;
    }

    private static void enviar(UUID eventoId, String clave, String carga) {
        try (var productor = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            var registro = new ProducerRecord<>(TEMA, clave, carga);
            registro.headers().add("evento-id", eventoId.toString().getBytes(StandardCharsets.UTF_8));
            registro.headers().add("evento-tipo", "notice.requested".getBytes(StandardCharsets.UTF_8));
            registro.headers().add("correlacion-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            productor.send(registro).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MimeMessage esperarCorreo(String destinatario) {
        Predicate<MimeMessage> para = m -> {
            try {
                return Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().equals(destinatario));
            } catch (Exception e) {
                return false;
            }
        };
        esperar(() -> Arrays.stream(smtp.getReceivedMessages()).anyMatch(para));
        return Arrays.stream(smtp.getReceivedMessages()).filter(para).findFirst().orElseThrow();
    }

    private static ConsumerRecord<String, String> esperarEn(String tema, UUID eventoId) {
        try (var consumidor = consumidor(tema)) {
            Instant limite = Instant.now().plusSeconds(45);
            while (Instant.now().isBefore(limite)) {
                for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(500))) {
                    if (eventoId.toString().equals(cabecera(r, "evento-id"))) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("No llegó el evento " + eventoId + " al tema " + tema);
    }

    private static boolean llega(String tema, UUID eventoId, Duration ventana) {
        try (var consumidor = consumidor(tema)) {
            Instant limite = Instant.now().plus(ventana);
            while (Instant.now().isBefore(limite)) {
                for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(300))) {
                    if (eventoId.toString().equals(cabecera(r, "evento-id"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static KafkaConsumer<String, String> consumidor(String tema) {
        var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumidor.subscribe(List.of(tema));
        return consumidor;
    }

    private static String cabecera(ConsumerRecord<String, String> r, String nombre) {
        var h = r.headers().lastHeader(nombre);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void esperar(BooleanSupplier condicion) {
        Instant limite = Instant.now().plusSeconds(45);
        while (Instant.now().isBefore(limite)) {
            if (condicion.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("La condición no se cumplió en 45 segundos");
    }

    /** Roles y migraciones del repositorio de bases: los mismos que aplica el despliegue. */
    private static void prepararBase() {
        try {
            Path dir = Path.of(System.getProperty("redvital.migraciones.dir",
                    "../../../BDs_RedVital/databases/notifications")).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir.resolve("migraciones"))) {
                throw new IllegalStateException("No se encontraron las migraciones en " + dir);
            }
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(dir.resolve("00_roles.sql")), "/tmp/00_roles.sql");
            var resultado = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", "db_notifications",
                    "-v", "password_propietario=" + CLAVE_PROPIETARIO, "-v", "password_servicio=" + CLAVE_SERVICIO,
                    "-f", "/tmp/00_roles.sql");
            if (resultado.getExitCode() != 0) {
                throw new IllegalStateException("Falló 00_roles.sql: " + resultado.getStderr());
            }
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), "notifications_propietario", CLAVE_PROPIETARIO)
                    .locations("filesystem:" + dir.resolve("migraciones"))
                    .validateMigrationNaming(true)
                    .load()
                    .migrate();
        } catch (Exception e) {
            throw new IllegalStateException("No fue posible preparar la base de pruebas", e);
        }
    }

    private static void crearTemas() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var temas = new java.util.ArrayList<NewTopic>();
            temas.add(new NewTopic(TEMA, 3, (short) 1));
            for (int n = 0; n <= 5; n++) {
                temas.add(new NewTopic(TEMA + ".notificaciones.retry-" + n, 3, (short) 1));
            }
            temas.add(new NewTopic(DLT, 1, (short) 1));
            admin.createTopics(temas).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("No fue posible crear los temas de prueba", e);
        }
    }

    private static GreenMail iniciarSmtp() {
        var servidor = new GreenMail(new ServerSetup(PUERTO_SMTP, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        servidor.setUser("redvital.qa@correo.co", "redvital.qa@correo.co", "abcdefghijklmnop");
        servidor.start();
        return servidor;
    }

    private static int puertoLibre() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
