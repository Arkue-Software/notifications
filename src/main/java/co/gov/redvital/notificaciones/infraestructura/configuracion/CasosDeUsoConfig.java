package co.gov.redvital.notificaciones.infraestructura.configuracion;

import co.gov.redvital.notificaciones.aplicacion.CanalCorreoPort;
import co.gov.redvital.notificaciones.aplicacion.ContactosPort;
import co.gov.redvital.notificaciones.aplicacion.EntregarAviso;
import co.gov.redvital.notificaciones.aplicacion.Plantillas;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CasosDeUsoConfig {

    @Bean
    Plantillas plantillas(PropiedadesNotificaciones propiedades) {
        return new Plantillas(propiedades.sitio(), ZoneId.of(propiedades.zonaHoraria()));
    }

    @Bean
    EntregarAviso entregarAviso(ContactosPort contactos, Plantillas plantillas, CanalCorreoPort canal) {
        return new EntregarAviso(contactos, plantillas, canal);
    }
}
