package co.gov.redvital.notificaciones.infraestructura.configuracion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del trabajador. Los secretos llegan como archivos en
 * /run/secrets (configtree) y nunca se escriben en el repositorio.
 */
@ConfigurationProperties(prefix = "redvital")
public record PropiedadesNotificaciones(
        @DefaultValue("https://localhost") String sitio,
        @DefaultValue("America/Bogota") String zonaHoraria,
        @DefaultValue("3s") Duration plazo,
        Servicio donacion,
        Identidad identidad,
        Correo correo) {

    public record Servicio(String url) {
    }

    /** Credencial del cliente notificaciones para pedir el token de servicio. */
    public record Identidad(String url, @DefaultValue("notificaciones") String cliente, String secreto) {
    }

    /** Proveedor SMTP. En QA, Gmail con una contraseña de aplicación. */
    public record Correo(@DefaultValue("smtp.gmail.com") String host, @DefaultValue("587") int puerto, String usuario,
                         String clave, @DefaultValue("true") boolean starttls, String remitente,
                         @DefaultValue("RedVital") String nombreRemitente) {

        /** El remitente: el configurado o, si no hay, la cuenta con la que se autentica. */
        public String direccionRemitente() {
            return remitente != null && !remitente.isBlank() ? remitente.trim() : usuario == null ? "" : usuario.trim();
        }
    }
}
