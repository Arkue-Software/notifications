package co.gov.redvital.notificaciones.infraestructura.correo;

import static org.assertj.core.api.Assertions.assertThat;

import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.util.Map;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class ClasificadorSmtpTest {

    private static MailSendException envioFallido(Exception porMensaje) {
        return new MailSendException(Map.of(new Object(), porMensaje));
    }

    @Test
    void credencial_rechazada_o_sin_conexion_es_canal_no_disponible() {
        assertThat(ClasificadorSmtp.clasificar(new MailAuthenticationException("535 credenciales")))
                .isInstanceOf(FalloEntrega.CanalNoDisponible.class);
        assertThat(ClasificadorSmtp.clasificar(envioFallido(new MessagingException("sin conexión", new ConnectException("rechazada")))))
                .isInstanceOf(FalloEntrega.CanalNoDisponible.class);
    }

    @Test
    void un_codigo_4xx_es_temporal() throws Exception {
        var temporal = new SMTPSendFailedException("DATA", 451, "451 intente luego", null, new Address[0], new Address[0], new Address[0]);
        assertThat(ClasificadorSmtp.clasificar(envioFallido(temporal))).isInstanceOf(FalloEntrega.CanalNoDisponible.class);
    }

    @Test
    void un_destinatario_rechazado_y_un_rechazo_del_proveedor_se_distinguen() throws Exception {
        var direccion = new SMTPAddressFailedException(new InternetAddress("nadie@correo.co"), "RCPT TO", 550, "550 no existe");
        assertThat(ClasificadorSmtp.clasificar(envioFallido(direccion)))
                .isInstanceOfSatisfying(FalloEntrega.Rechazo.class, r -> assertThat(r.codigo()).isEqualTo("destinatario_rechazado"));

        var contenido = new SMTPSendFailedException("DATA", 554, "554 mensaje rechazado", null, new Address[0], new Address[0], new Address[0]);
        assertThat(ClasificadorSmtp.clasificar(envioFallido(contenido)))
                .isInstanceOfSatisfying(FalloEntrega.Rechazo.class, r -> assertThat(r.codigo()).isEqualTo("rechazo_proveedor"));
    }
}
