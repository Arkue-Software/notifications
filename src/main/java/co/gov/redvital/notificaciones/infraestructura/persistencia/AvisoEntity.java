package co.gov.redvital.notificaciones.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "avisos", schema = "notifications")
public class AvisoEntity {

    @Id
    private UUID id;

    @Column(name = "plantilla_id")
    private UUID plantillaId;

    @Column(name = "canal", nullable = false)
    private String canal;

    @Column(name = "destinatario", nullable = false)
    private String destinatario;

    @Column(name = "asunto", length = 500)
    private String asunto;

    @Column(name = "contenido", columnDefinition = "TEXT")
    private String contenido;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parametros", columnDefinition = "jsonb")
    private Map<String, String> parametros;

    @Column(name = "estado", nullable = false)
    private String estado;

    @Column(name = "intentos", nullable = false)
    private Integer intentos;

    @Column(name = "max_intentos", nullable = false)
    private Integer maxIntentos;

    @Column(name = "fecha_creacion", insertable = false, updatable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "fecha_programada")
    private OffsetDateTime fechaProgramada;

    @Column(name = "fecha_entrega")
    private OffsetDateTime fechaEntrega;

    @Column(name = "fecha_actualizacion", insertable = false, updatable = false)
    private OffsetDateTime fechaActualizacion;

    // Getters y Setters (Asegúrate de que no haya llaves extra antes de esto)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPlantillaId() { return plantillaId; }
    public void setPlantillaId(UUID plantillaId) { this.plantillaId = plantillaId; }

    public String getCanal() { return canal; }
    public void setCanal(String canal) { this.canal = canal; }

    public String getDestinatario() { return destinatario; }
    public void setDestinatario(String destinatario) { this.destinatario = destinatario; }

    public String getAsunto() { return asunto; }
    public void setAsunto(String asunto) { this.asunto = asunto; }

    public String getContenido() { return contenido; }
    public void setContenido(String contenido) { this.contenido = contenido; }

    public Map<String, String> getParametros() { return parametros; }
    public void setParametros(Map<String, String> parametros) { this.parametros = parametros; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Integer getIntentos() { return intentos; }
    public void setIntentos(Integer intentos) { this.intentos = intentos; }

    public Integer getMaxIntentos() { return maxIntentos; }
    public void setMaxIntentos(Integer maxIntentos) { this.maxIntentos = maxIntentos; }

    public OffsetDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(OffsetDateTime fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    public OffsetDateTime getFechaProgramada() { return fechaProgramada; }
    public void setFechaProgramada(OffsetDateTime fechaProgramada) { this.fechaProgramada = fechaProgramada; }

    public OffsetDateTime getFechaEntrega() { return fechaEntrega; }
    public void setFechaEntrega(OffsetDateTime fechaEntrega) { this.fechaEntrega = fechaEntrega; }

    public OffsetDateTime getFechaActualizacion() { return fechaActualizacion; }
    public void setFechaActualizacion(OffsetDateTime fechaActualizacion) { this.fechaActualizacion = fechaActualizacion; }
} // <-- Esta debe ser la única llave de cierre al final del archivo