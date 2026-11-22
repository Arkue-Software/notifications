package co.gov.redvital.notificaciones.infraestructura.correo;

import co.gov.redvital.notificaciones.aplicacion.CanalCorreoPort;
import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import co.gov.redvital.notificaciones.infraestructura.configuracion.PropiedadesNotificaciones;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Adaptador de envío por SMTP. Cada mensaje lleva un Message-ID fijo, derivado
 * del identificador del aviso: es la clave de idempotencia ante el canal (DD,
 * 9.2). SMTP no acepta claves de idempotencia; Gmail, en la práctica, descarta
 * en el buzón los duplicados con el mismo Message-ID, pero no lo garantiza. La
 * reentrega tras una caída entre el envío y la confirmación es el riesgo
 * residual que el DD acepta, preferible a perder un aviso.
 */
@Component
class CanalSmtp implements CanalCorreoPort {

    private final JavaMailSenderImpl remitente;
    private final PropiedadesNotificaciones.Correo config;

    CanalSmtp(JavaMailSenderImpl remitente, PropiedadesNotificaciones propiedades) {
        this.remitente = remitente;
        this.config = propiedades.correo();
    }

    @Override
    public void enviar(Mensaje m) {
        if (config.direccionRemitente().isBlank()) {
            throw new FalloEntrega.CanalNoDisponible("El correo no está configurado: falta SMTP_USUARIO.", null);
        }
        MimeMessage mensaje;
        try {
            mensaje = new MensajeConIdFijo(remitente.getSession(), m.idMensaje());
            var ayudante = new MimeMessageHelper(mensaje, true, "UTF-8");
            ayudante.setFrom(new InternetAddress(config.direccionRemitente(), config.nombreRemitente(), "UTF-8"));
            ayudante.setTo(new InternetAddress(m.destinatario(), true));
            ayudante.setSubject(m.asunto());
            ayudante.setText(m.texto(), m.html());
            mensaje.setHeader("Auto-Submitted", "auto-generated"); // RFC 3834: que nadie le responda en automático
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new FalloEntrega.Rechazo(FalloEntrega.Rechazo.DESTINATARIO,
                    "El correo del destinatario no es una dirección válida.", e);
        }
        try {
            remitente.send(mensaje);
        } catch (MailException e) {
            throw ClasificadorSmtp.clasificar(e);
        }
    }

    /** MimeMessage que conserva el Message-ID dado en lugar de generar uno al guardar. */
    private static final class MensajeConIdFijo extends MimeMessage {

        private final String id;

        MensajeConIdFijo(Session sesion, String id) {
            super(sesion);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", id);
        }
    }
}
