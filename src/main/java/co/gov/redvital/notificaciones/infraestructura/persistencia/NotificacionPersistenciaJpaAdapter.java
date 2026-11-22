package co.gov.redvital.notificaciones.infraestructura.persistencia;

import co.gov.redvital.notificaciones.aplicacion.NotificacionPersistenciaPort;
import co.gov.redvital.notificaciones.aplicacion.RegistroIdempotencia;
import org.springframework.stereotype.Component;

@Component
public class NotificacionPersistenciaJpaAdapter implements NotificacionPersistenciaPort {

    private final NotificacionJpaRepository repository;

    public NotificacionPersistenciaJpaAdapter(NotificacionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void guardar(RegistroIdempotencia registro) {
        // Usamos la entidad de idempotencia que ya existe en tu proyecto
        BandejaIdempotenciaEntity entity = new BandejaIdempotenciaEntity();
        
        // Mapeo desde el Record de Dominio a la Entidad JPA
        entity.setClaveIdempotencia(registro.claveIdempotencia());
        entity.setEventoOrigen(registro.eventoOrigen());
        entity.setAvisoId(registro.avisoId());
        entity.setResultado(registro.resultado());

        repository.save(entity);
    }
}