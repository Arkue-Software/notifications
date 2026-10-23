package co.gov.redvital.notificaciones.infraestructura.mensajeria;

import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Tratamiento de fallos del consumidor (DD, Tabla 17):
 * <ul>
 *   <li>{@code canal_no_disponible}: el proveedor de correo, Donación o
 *   Identidad no responden. El consumidor pausa y reintenta el mismo aviso
 *   con espera creciente desde 15 s hasta 5 min, sin límite: avanzar solo
 *   llenaría los temas de reintento con avisos que fallarían igual.</li>
 *   <li>Rechazos del destinatario o del proveedor: temas
 *   {@code <tema>.notificaciones.retry-N} sin detener la partición, hasta ocho
 *   intentos, y luego {@code <tema>.notificaciones.dlt}.</li>
 *   <li>Aviso inválido: directo a mensajes muertos (ver {@link ConsumidorAvisos}).</li>
 * </ul>
 * Todo mensaje reenviado lleva la cabecera {@value #CABECERA_RESULTADO} con el
 * código del resultado. Los temas los crea el trabajo de inicialización de Kafka.
 */
@Configuration
class KafkaConfig extends RetryTopicConfigurationSupport {

    static final String SUFIJO_REINTENTO = ".notificaciones.retry";
    static final String SUFIJO_DLT = ".notificaciones.dlt";
    static final String CABECERA_RESULTADO = "aviso-resultado";

    private final Duration esperaCanal;
    private final Duration esperaCanalMaxima;

    KafkaConfig(@Value("${redvital.reintentos.espera-canal:15s}") Duration esperaCanal,
                @Value("${redvital.reintentos.espera-canal-maxima:5m}") Duration esperaCanalMaxima) {
        this.esperaCanal = esperaCanal;
        this.esperaCanalMaxima = esperaCanalMaxima;
    }

    @Override
    protected void configureBlockingRetries(BlockingRetriesConfigurer reintentos) {
        var espera = new ExponentialBackOff(esperaCanal.toMillis(), 2.0);
        espera.setMaxInterval(esperaCanalMaxima.toMillis());
        reintentos.retryOn(FalloEntrega.CanalNoDisponible.class).backOff(espera);
    }

    @Override
    protected Consumer<DeadLetterPublishingRecovererFactory> configureDeadLetterPublishingContainerFactory() {
        return fabrica -> fabrica.setHeadersFunction((registro, error) -> {
            var cabeceras = new RecordHeaders();
            cabeceras.add(CABECERA_RESULTADO, codigo(error).getBytes(StandardCharsets.UTF_8));
            return cabeceras;
        });
    }

    /** Programador propio de las esperas de los temas de reintento: no depende de @EnableScheduling. */
    @Bean
    RetryTopicSchedulerWrapper programadorReintentos() {
        var programador = new ThreadPoolTaskScheduler();
        programador.setThreadNamePrefix("kafka-reintento-");
        return new RetryTopicSchedulerWrapper(programador); // lo inicia y lo detiene con el contexto
    }

    static String codigo(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof FalloEntrega fallo) {
                return fallo.codigo();
            }
        }
        return "error_inesperado";
    }
}
