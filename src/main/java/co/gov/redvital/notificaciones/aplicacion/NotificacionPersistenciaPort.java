package co.gov.redvital.notificaciones.aplicacion;

public interface NotificacionPersistenciaPort {

    void guardar(RegistroIdempotencia registro);
}
