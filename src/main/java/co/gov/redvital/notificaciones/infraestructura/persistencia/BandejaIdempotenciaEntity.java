package co.gov.redvital.notificaciones.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "bandeja_idempotencia", schema = "notifications")
public class BandejaIdempotenciaEntity {

    @Id
    @Column(name = "clave_idempotencia", nullable = false)
    private String claveIdempotencia;

    @Column(name = "evento_origen", nullable = false)
    private String eventoOrigen;

    @Column(name = "aviso_id")
    private UUID avisoId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resultado", columnDefinition = "jsonb")
    private Map<String, String> resultado;

    // Constructor vacío requerido por JPA
    public BandejaIdempotenciaEntity() {
    }

    // Getters y Setters
    public String getClaveIdempotencia() {
        return claveIdempotencia;
    }

    public void setClaveIdempotencia(String claveIdempotencia) {
        this.claveIdempotencia = claveIdempotencia;
    }

    public String getEventoOrigen() {
        return eventoOrigen;
    }

    public void setEventoOrigen(String eventoOrigen) {
        this.eventoOrigen = eventoOrigen;
    }

    public UUID getAvisoId() {
        return avisoId;
    }

    public void setAvisoId(UUID avisoId) {
        this.avisoId = avisoId;
    }

    public Map<String, String> getResultado() {
        return resultado;
    }

    public void setResultado(Map<String, String> resultado) {
        this.resultado = resultado;
    }
}