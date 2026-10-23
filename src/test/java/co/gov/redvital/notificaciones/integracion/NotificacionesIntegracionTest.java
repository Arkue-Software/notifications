package co.gov.redvital.notificaciones.integracion;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

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
    private static final String TOKEN = "token-de-servicio-de-prueba";

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    static final int PUERTO_SMTP = puertoLibre();
    static volatile GreenMail smtp;
    /** Respuesta de Donación por donante: estado y cuerpo. */
    static final Map<UUID, Map.Entry<Integer, String>> CONTACTOS = new ConcurrentHashMap<>();
    static final HttpServer SERVICIOS = sustitutoDeServicios();

    static {
        KAFKA.start();
        crearTemas();
        smtp = iniciarSmtp();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.security.protocol", () -> "PLAINTEXT");
        String servicios = "http://127.0.0.1:" + SERVICIOS.getAddress().getPort();
        r.add("redvital.donacion.url", () -> servicios);
        r.add("redvital.identidad.url", () -> servicios);
        r.add("redvital.identidad.secreto", () -> "secreto-de-prueba");
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
    MeterRegistry metricas;

    @AfterAll
    static void detener() {
        SERVICIOS.stop(0);
        smtp.stop();
    }

    @Test
    void confirma_la_cuenta_por_correo_con_el_enlace_al_sitio() throws Exception {
        UUID donante = UUID.randomUUID();
        String correo = "laura." + donante.toString().substring(0, 8) + "@correo.co";
        CONTACTOS.put(donante, Map.entry(200, "{\"contactable\":true,\"correo\":\"" + correo + "\",\"nombre\":\"Laura Pérez\"}"));
        UUID aviso = publicar(donante, "cuenta_creada", "{}");

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
    void sin_contacto_o_con_un_dominio_reservado_no_se_envia_nada_y_no_es_un_fallo() throws Exception {
        UUID sinContacto = UUID.randomUUID();
        CONTACTOS.put(sinContacto, Map.entry(200, "{\"contactable\":false}"));
        UUID reservado = UUID.randomUUID();
        CONTACTOS.put(reservado, Map.entry(200, "{\"contactable\":true,\"correo\":\"donante.1@redvital.test\",\"nombre\":\"N\"}"));
        int antes = smtp.getReceivedMessages().length;
        UUID avisoSinContacto = publicar(sinContacto, "cuenta_creada", "{}");
        UUID avisoReservado = publicar(reservado, "cuenta_creada", "{}");

        esperar(() -> contador("cuenta_creada", "destinatario_sin_contacto") >= 1
                && contador("cuenta_creada", "dominio_reservado") >= 1);
        assertThat(smtp.getReceivedMessages()).hasSize(antes);
        assertThat(llega(DLT, avisoSinContacto, Duration.ofSeconds(2))).isFalse();
        assertThat(llega(DLT, avisoReservado, Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void un_destinatario_que_donacion_no_reconoce_va_directo_a_mensajes_muertos() {
        UUID aviso = publicar(UUID.randomUUID(), "cuenta_creada", "{}"); // el sustituto responde 404
        ConsumerRecord<String, String> muerto = esperarEn(DLT, aviso);
        assertThat(cabecera(muerto, "aviso-resultado")).isEqualTo("destinatario_inexistente");
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
        UUID donante = UUID.randomUUID();
        CONTACTOS.put(donante, Map.entry(200, "{\"contactable\":true,\"correo\":\"esto no es un correo\",\"nombre\":\"N\"}"));
        UUID aviso = publicar(donante, "cuenta_creada", "{}");

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
        CONTACTOS.put(donante, Map.entry(200, "{\"contactable\":true,\"correo\":\"" + correo + "\",\"nombre\":\"Ana\"}"));
        smtp.stop();
        try {
            UUID aviso = publicar(donante, "cuenta_creada", "{}");
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
        CONTACTOS.put(donante, Map.entry(200, "{\"contactable\":true,\"correo\":\"" + correo + "\",\"nombre\":\"Ana\"}"));
        publicar(donante, "campania_publicada", "{\"nombre\":\"Jornada Parque Explora\",\"sede\":\"Plazoleta\","
                + "\"inicia_en\":\"2026-10-28T13:00:00Z\",\"termina_en\":\"2026-10-29T22:00:00Z\"}");
        assertThat(esperarCorreo(correo).getSubject()).isEqualTo("Nueva jornada de donación: Jornada Parque Explora");
    }

    // ------------------------------------------------------------------ utilidades

    private double contador(String tipo, String resultado) {
        Counter c = metricas.find("redvital_avisos").tag("tipo", tipo).tag("resultado", resultado).counter();
        return c == null ? 0 : c.count();
    }

    private static UUID publicar(UUID donante, String tipo, String plantilla) {
        UUID aviso = UUID.randomUUID();
        enviar(aviso, donante.toString(), "{\"aviso_id\":\"" + aviso + "\",\"tipo_aviso\":\"" + tipo
                + "\",\"destinatario_tipo\":\"donante\",\"destinatario_id\":\"" + donante
                + "\",\"campania_id\":null,\"plantilla\":" + plantilla + ",\"generado_en\":\"" + Instant.now() + "\"}");
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

    /** Identidad (token de servicio) y Donación (contacto del donante), en un solo servidor HTTP. */
    private static HttpServer sustitutoDeServicios() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/internal/v1/sesiones/servicio", ex -> responder(ex, 200,
                    "{\"token_acceso\":\"" + TOKEN + "\",\"expira_en\":900}"));
            servidor.createContext("/internal/v1/donantes/", ex -> {
                if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    responder(ex, 401, "{}");
                    return;
                }
                String[] partes = ex.getRequestURI().getPath().split("/");
                var respuesta = CONTACTOS.get(UUID.fromString(partes[4]));
                if (respuesta == null) {
                    responder(ex, 404, "{\"tipo\":\"no-encontrado\"}");
                } else {
                    responder(ex, respuesta.getKey(), respuesta.getValue());
                }
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void responder(HttpExchange ex, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(estado, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static int puertoLibre() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
