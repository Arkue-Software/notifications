package co.gov.redvital.notificaciones.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "bandeja_idempotencia", schema = "notifications")
public class BandejaIdempotenciaEntity implements Persistable<String> {

    @Id
    @Column(name = "clave_idempotencia", nullable = false, length = 255)
    private String claveIdempotencia;

    @Column(name = "evento_origen", nullable = false, length = 100)
    private String eventoOrigen;

    @Column(name = "aviso_id")
    private UUID avisoId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resultado", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> resultado;

    @Column(name = "procesado_en", nullable = false)
    private Instant procesadoEn;

    @Transient
    private boolean nuevo = true;

    protected BandejaIdempotenciaEntity() {
    }

    BandejaIdempotenciaEntity(String claveIdempotencia, String eventoOrigen, UUID avisoId, Map<String, String> resultado) {
        this.claveIdempotencia = claveIdempotencia;
        this.eventoOrigen = eventoOrigen;
        this.avisoId = avisoId;
        this.resultado = resultado;
        this.procesadoEn = Instant.now();
    }

    @Override
    public String getId() {
        return claveIdempotencia;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    @PostLoad
    @PostPersist
    private void marcarExistente() {
        nuevo = false;
    }
}
