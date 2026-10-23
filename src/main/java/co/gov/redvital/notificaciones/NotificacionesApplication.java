package co.gov.redvital.notificaciones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Trabajador de Notificaciones (ST1; DD, Sección 9). Consume EV-02
 * {@code notice.requested} y entrega cada aviso por correo. No tiene base de
 * datos ni rutas publicadas: el estado de un aviso es su posición en el tema.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificacionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificacionesApplication.class, args);
    }
}
