package co.gov.redvital.notificaciones.infraestructura.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificacionJpaRepository extends JpaRepository<BandejaIdempotenciaEntity, String> {
}