package co.gov.redvital.notificaciones.aplicacion;

import java.util.Map;
import java.util.UUID;

/**
 * Un aviso de EV-02 {@code notice.requested} ya validado contra el contrato.
 * Trae el correo y el nombre del destinatario, que Donación resolvió al
 * decidir el aviso: los servicios solo se comunican por Kafka, así que el
 * trabajador no le pregunta nada a nadie. Más los datos no personales de la
 * plantilla.
 */
public record Aviso(UUID avisoId, String tipo, String destinatarioTipo, UUID destinatarioId, String correo,
                    String nombre, UUID campaniaId, Map<String, String> plantilla) {

    public static final String CUENTA_CREADA = "cuenta_creada";
    public static final String CAMPANIA_PUBLICADA = "campania_publicada";

    public Aviso {
        plantilla = plantilla == null ? Map.of() : Map.copyOf(plantilla);
    }
}
