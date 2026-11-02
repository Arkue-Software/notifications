package co.gov.redvital.notificaciones.infraestructura.persistencia;

import co.gov.redvital.notificaciones.aplicacion.NotificacionPersistenciaPort;
import co.gov.redvital.notificaciones.aplicacion.RegistroIdempotencia;
import org.springframework.stereotype.Repository;

@Repository
class NotificacionPersistenciaJpaAdapter implements NotificacionPersistenciaPort {

    private final NotificacionJpaRepository repositorio;

    NotificacionPersistenciaJpaAdapter(NotificacionJpaRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public void guardar(RegistroIdempotencia registro) {
        var entidad = new BandejaIdempotenciaEntity(registro.claveIdempotencia(), registro.eventoOrigen(),
                registro.avisoId(), registro.resultado());
        repositorio.save(entidad);
    }
}
