package co.gov.redvital.notificaciones.infraestructura.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificacionJpaRepository extends JpaRepository<BandejaIdempotenciaEntity, String> {
}
