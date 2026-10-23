package co.gov.redvital.notificaciones.infraestructura.mensajeria;

import co.gov.redvital.notificaciones.aplicacion.Aviso;
import co.gov.redvital.notificaciones.aplicacion.EntregarAviso;
import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import co.gov.redvital.notificaciones.aplicacion.Resultado;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de EV-02 {@code notice.requested} (grupo notificaciones). La
 * posición se confirma solo después de entregar o de clasificar el resultado
 * como terminal (DD, 9.1). Nunca registra el correo ni el nombre del
 * destinatario: solo el identificador del aviso, su tipo y el resultado.
 */
@Component
class ConsumidorAvisos {

    static final String TEMA = "redvital.donacion.notice-requested.v1";
    private static final Logger LOG = LoggerFactory.getLogger(ConsumidorAvisos.class);

    private final EntregarAviso entregar;
    private final JsonMapper json;
    private final MeterRegistry metricas;
    private final Counter mensajesMuertos;

    ConsumidorAvisos(EntregarAviso entregar, JsonMapper json, MeterRegistry metricas) {
        this.entregar = entregar;
        this.json = json;
        this.metricas = metricas;
        this.mensajesMuertos = Counter.builder("redvital_eventos_mensajes_muertos") // Prometheus: ..._total
                .description("Eventos que el consumidor no pudo aplicar y quedaron en el tema de mensajes muertos")
                .tag("tema", TEMA)
                .tag("grupo", "notificaciones")
                .register(metricas);
    }

    @RetryableTopic(
            attempts = "8",
            backOff = @BackOff(delayString = "${redvital.reintentos.espera-inicial:15000}", multiplier = 2,
                    maxDelayString = "${redvital.reintentos.espera-maxima:300000}"),
            retryTopicSuffix = KafkaConfig.SUFIJO_REINTENTO,
            dltTopicSuffix = KafkaConfig.SUFIJO_DLT,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = FalloEntrega.AvisoInvalido.class)
    @KafkaListener(topics = TEMA, groupId = "notificaciones")
    void consumir(ConsumerRecord<String, String> registro) {
        String eventoId = cabecera(registro, "evento-id").orElse("sin evento-id");
        MDC.put("correlacion_id", cabecera(registro, "correlacion-id").filter(c -> c.length() <= 36).orElse(eventoId));
        String tipo = "desconocido";
        try {
            Aviso aviso = leer(registro);
            tipo = aviso.tipo();
            Resultado resultado = entregar.ejecutar(aviso);
            contar(tipo, resultado.codigo());
            LOG.info("Aviso {} ({}): {}", aviso.avisoId(), tipo, resultado.codigo());
        } catch (FalloEntrega fallo) {
            contar(tipo, fallo.codigo());
            LOG.warn("Aviso {} ({}): {} — {}", eventoId, tipo, fallo.codigo(), fallo.getMessage());
            throw fallo;
        } finally {
            MDC.remove("correlacion_id");
        }
    }

    /**
     * Un aviso en mensajes muertos nunca se descarta en silencio (DD, 9.2): queda
     * en el tema, se registra como error y alimenta la métrica de alerta.
     */
    @DltHandler
    void mensajeMuerto(ConsumerRecord<String, String> registro) {
        mensajesMuertos.increment();
        LOG.error("Aviso {} en mensajes muertos ({}/{}): {}", cabecera(registro, "evento-id").orElse("sin evento-id"),
                registro.topic(), registro.offset(), cabecera(registro, KafkaConfig.CABECERA_RESULTADO).orElse("sin resultado"));
    }

    private void contar(String tipo, String resultado) {
        metricas.counter("redvital_avisos", "tipo", tipo, "resultado", resultado).increment();
    }

    /** Valida la carga contra el contrato de EV-02; tolera los campos que no conoce. */
    private Aviso leer(ConsumerRecord<String, String> registro) {
        try {
            JsonNode n = json.readTree(registro.value());
            UUID avisoId = uuid(n.path("aviso_id").asString(null));
            UUID destinatario = uuid(n.path("destinatario_id").asString(null));
            String tipo = n.path("tipo_aviso").asString(null);
            String destinatarioTipo = n.path("destinatario_tipo").asString(null);
            if (avisoId == null || destinatario == null || tipo == null || destinatarioTipo == null) {
                throw malFormado("Faltan campos obligatorios del contrato.");
            }
            Map<String, String> plantilla = new HashMap<>();
            n.path("plantilla").properties().forEach(campo -> {
                if (campo.getValue().isString()) {
                    plantilla.put(campo.getKey(), campo.getValue().asString());
                }
            });
            String campania = n.path("campania_id").asString(null);
            return new Aviso(avisoId, tipo, destinatarioTipo, destinatario, campania == null ? null : uuid(campania),
                    plantilla);
        } catch (JacksonException e) {
            throw malFormado("La carga no es JSON válido.");
        }
    }

    private static UUID uuid(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            throw malFormado("Un identificador no es un UUID válido.");
        }
    }

    private static FalloEntrega.AvisoInvalido malFormado(String detalle) {
        return new FalloEntrega.AvisoInvalido("evento_mal_formado", detalle);
    }

    private static Optional<String> cabecera(ConsumerRecord<String, String> registro, String nombre) {
        Header h = registro.headers().lastHeader(nombre);
        return h == null || h.value() == null ? Optional.empty() : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }
}
