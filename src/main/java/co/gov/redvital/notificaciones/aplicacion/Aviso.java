package co.gov.redvital.notificaciones.aplicacion;

import java.util.Map;
import java.util.UUID;

/**
 * Un aviso de EV-02 {@code notice.requested} ya validado contra el contrato.
 * Trae identificadores y los datos no personales de la plantilla; el correo y
 * el nombre del destinatario se piden a su dueño al entregar (DD, Sección 9).
 */
public record Aviso(UUID avisoId, String tipo, String destinatarioTipo, UUID destinatarioId, UUID campaniaId,
                    Map<String, String> plantilla) {

    public static final String CUENTA_CREADA = "cuenta_creada";
    public static final String CAMPANIA_PUBLICADA = "campania_publicada";

    public Aviso {
        plantilla = plantilla == null ? Map.of() : Map.copyOf(plantilla);
    }
}
