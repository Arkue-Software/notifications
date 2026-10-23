package co.gov.redvital.notificaciones.infraestructura.correo;

import co.gov.redvital.notificaciones.infraestructura.configuracion.PropiedadesNotificaciones;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Cliente SMTP. Con Gmail: smtp.gmail.com, puerto 587, STARTTLS obligatorio y
 * una contraseña de aplicación de la cuenta del proyecto. Plazos de diez
 * segundos: un proveedor colgado no detiene el consumo para siempre, se
 * clasifica como canal no disponible.
 */
@Configuration
class ConfiguracionCorreo {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionCorreo.class);
    private static final String PLAZO_MS = "10000";

    @Bean
    JavaMailSenderImpl remitenteCorreo(PropiedadesNotificaciones propiedades) {
        var correo = propiedades.correo();
        var remitente = new JavaMailSenderImpl();
        remitente.setHost(correo.host());
        remitente.setPort(correo.puerto());
        remitente.setDefaultEncoding("UTF-8");
        boolean conCredencial = correo.usuario() != null && !correo.usuario().isBlank();
        if (conCredencial) {
            remitente.setUsername(correo.usuario().trim());
            // Gmail muestra la contraseña de aplicación en grupos separados por espacios.
            remitente.setPassword(correo.clave() == null ? "" : correo.clave().replaceAll("\\s", ""));
        }
        Properties p = remitente.getJavaMailProperties();
        p.setProperty("mail.transport.protocol", "smtp");
        p.setProperty("mail.smtp.auth", String.valueOf(conCredencial));
        p.setProperty("mail.smtp.starttls.enable", String.valueOf(correo.starttls()));
        p.setProperty("mail.smtp.starttls.required", String.valueOf(correo.starttls()));
        p.setProperty("mail.smtp.connectiontimeout", PLAZO_MS);
        p.setProperty("mail.smtp.timeout", PLAZO_MS);
        p.setProperty("mail.smtp.writetimeout", PLAZO_MS);
        if (!conCredencial || correo.clave() == null || correo.clave().isBlank()) {
            LOG.warn("El correo no está configurado del todo (SMTP_USUARIO o la clave SMTP vacíos): "
                    + "los avisos esperarán en Kafka hasta que se configure.");
        }
        return remitente;
    }
}
