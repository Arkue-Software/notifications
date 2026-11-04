package co.gov.redvital.notificaciones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Trabajador de Notificaciones (ST1; DD, Sección 9). Consume EV-02
 * {@code notice.requested}, entrega los avisos por correo y permite persistir
 * registros de idempotencia en PostgreSQL. No publica rutas de negocio.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificacionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificacionesApplication.class, args);
    }
}
