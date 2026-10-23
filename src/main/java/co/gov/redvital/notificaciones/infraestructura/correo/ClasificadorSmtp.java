package co.gov.redvital.notificaciones.infraestructura.correo;

import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import jakarta.mail.SendFailedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

/**
 * Traduce un fallo del proveedor SMTP a un resultado de la Tabla 17 del DD:
 * <ul>
 *   <li>Sin conexión, plazo vencido, credencial rechazada o código 4xx
 *   (temporal): {@code canal_no_disponible}. No es culpa del destinatario.</li>
 *   <li>Código 5xx sobre el destinatario (550 a 553), o dirección inválida:
 *   {@code destinatario_rechazado}.</li>
 *   <li>Cualquier otro rechazo definitivo: {@code rechazo_proveedor}.</li>
 * </ul>
 */
final class ClasificadorSmtp {

    private static final Set<Integer> CODIGOS_DESTINATARIO = Set.of(550, 551, 552, 553);

    private ClasificadorSmtp() {
    }

    static FalloEntrega clasificar(MailException e) {
        if (e instanceof MailAuthenticationException) {
            return new FalloEntrega.CanalNoDisponible("El proveedor rechazó la credencial SMTP: revisa la contraseña de aplicación.", e);
        }
        for (Throwable causa : causas(e)) {
            if (causa instanceof SMTPAddressFailedException direccion) {
                return porCodigo(direccion.getReturnCode(), true, e);
            }
            if (causa instanceof SMTPSendFailedException envio) {
                return porCodigo(envio.getReturnCode(), false, e);
            }
            if (causa instanceof MailConnectException || causa instanceof IOException) {
                return new FalloEntrega.CanalNoDisponible("No hay conexión con el proveedor de correo.", e);
            }
            if (causa instanceof SendFailedException sinEnviar && sinEnviar.getInvalidAddresses() != null
                    && sinEnviar.getInvalidAddresses().length > 0) {
                return new FalloEntrega.Rechazo(FalloEntrega.Rechazo.DESTINATARIO, "El proveedor rechazó la dirección.", e);
            }
        }
        return new FalloEntrega.Rechazo(FalloEntrega.Rechazo.PROVEEDOR, "El proveedor rechazó el mensaje.", e);
    }

    private static FalloEntrega porCodigo(int codigo, boolean sobreDestinatario, MailException e) {
        if (codigo >= 400 && codigo < 500) {
            return new FalloEntrega.CanalNoDisponible("El proveedor respondió " + codigo + " (temporal).", e);
        }
        if (sobreDestinatario || CODIGOS_DESTINATARIO.contains(codigo)) {
            return new FalloEntrega.Rechazo(FalloEntrega.Rechazo.DESTINATARIO, "El proveedor rechazó al destinatario (" + codigo + ").", e);
        }
        return new FalloEntrega.Rechazo(FalloEntrega.Rechazo.PROVEEDOR, "El proveedor rechazó el mensaje (" + codigo + ").", e);
    }

    /** La excepción, sus causas y, en un envío fallido, las excepciones de cada mensaje con sus causas. */
    private static List<Throwable> causas(MailException e) {
        List<Throwable> todas = new ArrayList<>();
        agregar(todas, e);
        if (e instanceof MailSendException envio) {
            for (Exception porMensaje : envio.getMessageExceptions()) {
                agregar(todas, porMensaje);
            }
        }
        return todas;
    }

    private static void agregar(List<Throwable> todas, Throwable t) {
        for (Throwable actual = t; actual != null && !todas.contains(actual); actual = siguiente(actual)) {
            todas.add(actual);
        }
    }

    /** jakarta.mail encadena con getNextException además de getCause. */
    private static Throwable siguiente(Throwable t) {
        if (t instanceof jakarta.mail.MessagingException m && m.getNextException() != null) {
            return m.getNextException();
        }
        return t.getCause();
    }
}
