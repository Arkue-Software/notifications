package co.gov.redvital.notificaciones.aplicacion;

import java.util.UUID;

/** Contacto del destinatario, pedido a su dueño en el momento de entregar. */
public interface ContactosPort {

    record Contacto(boolean contactable, String correo, String nombre) {
    }

    /**
     * @throws FalloEntrega.AvisoInvalido si el destinatario no existe
     * @throws FalloEntrega.CanalNoDisponible si el dueño del dato no responde
     */
    Contacto deDonante(UUID donanteId, String tipoAviso);
}
