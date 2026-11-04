package co.gov.redvital.notificaciones.aplicacion;

import java.util.Locale;
import java.util.Set;

/**
 * Ciclo de entrega de un aviso (DD, 9.1): componer el mensaje con la
 * plantilla del tipo y entregarlo al correo que trae el aviso. El consumidor
 * confirma la posición en el tema solo cuando esto termina sin excepción.
 */
public final class EntregarAviso {

    /** Dominios reservados que no reciben correo (RFC 2606 y RFC 6761). */
    private static final Set<String> DOMINIOS_RESERVADOS = Set.of("test", "example", "invalid", "localhost",
            "example.com", "example.net", "example.org");

    private final Plantillas plantillas;
    private final CanalCorreoPort canal;

    public EntregarAviso(Plantillas plantillas, CanalCorreoPort canal) {
        this.plantillas = plantillas;
        this.canal = canal;
    }

    public Resultado ejecutar(Aviso aviso) {
        if (!"donante".equals(aviso.destinatarioTipo())) {
            throw new FalloEntrega.AvisoInvalido("destinatario_no_soportado",
                    "Este trabajador aún no entrega avisos a " + aviso.destinatarioTipo() + ".");
        }
        if (!plantillas.soporta(aviso.tipo())) {
            throw new FalloEntrega.AvisoInvalido("tipo_sin_plantilla", "No hay plantilla para el tipo " + aviso.tipo() + ".");
        }
        if (aviso.correo() == null || aviso.correo().isBlank()) {
            return Resultado.DESTINATARIO_SIN_CONTACTO;
        }
        if (reservado(aviso.correo())) {
            return Resultado.DOMINIO_RESERVADO;
        }
        canal.enviar(plantillas.componer(aviso, aviso.correo().trim(), aviso.nombre()));
        return Resultado.ENTREGADO;
    }

    static boolean reservado(String correo) {
        String dominio = correo.substring(correo.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String tld = dominio.substring(dominio.lastIndexOf('.') + 1);
        return DOMINIOS_RESERVADOS.contains(tld) || DOMINIOS_RESERVADOS.contains(dominio);
    }
}
