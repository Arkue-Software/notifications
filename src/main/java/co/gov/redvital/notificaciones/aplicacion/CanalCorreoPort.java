package co.gov.redvital.notificaciones.aplicacion;

/** Adaptador de envío. El identificador del mensaje es la clave de idempotencia ante el canal. */
public interface CanalCorreoPort {

    record Mensaje(String idMensaje, String destinatario, String asunto, String texto, String html) {
    }

    /**
     * @throws FalloEntrega.CanalNoDisponible si el proveedor no responde
     * @throws FalloEntrega.Rechazo si el proveedor rechaza el mensaje
     */
    void enviar(Mensaje mensaje);
}
