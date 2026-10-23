package co.gov.redvital.notificaciones.aplicacion;

import java.util.Locale;
import java.util.Set;

/**
 * Ciclo de entrega de un aviso (DD, 9.1): resolver el contacto en su dueño,
 * componer el mensaje con la plantilla del tipo y entregarlo. El consumidor
 * confirma la posición en el tema solo cuando esto termina sin excepción.
 */
public final class EntregarAviso {

    /** Dominios reservados que no reciben correo (RFC 2606 y RFC 6761). */
    private static final Set<String> DOMINIOS_RESERVADOS = Set.of("test", "example", "invalid", "localhost",
            "example.com", "example.net", "example.org");

    private final ContactosPort contactos;
    private final Plantillas plantillas;
    private final CanalCorreoPort canal;

    public EntregarAviso(ContactosPort contactos, Plantillas plantillas, CanalCorreoPort canal) {
        this.contactos = contactos;
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
        var contacto = contactos.deDonante(aviso.destinatarioId(), aviso.tipo());
        if (!contacto.contactable()) {
            return Resultado.DESTINATARIO_SIN_CONTACTO;
        }
        if (reservado(contacto.correo())) {
            return Resultado.DOMINIO_RESERVADO;
        }
        canal.enviar(plantillas.componer(aviso, contacto.correo(), contacto.nombre()));
        return Resultado.ENTREGADO;
    }

    static boolean reservado(String correo) {
        String dominio = correo.substring(correo.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String tld = dominio.substring(dominio.lastIndexOf('.') + 1);
        return DOMINIOS_RESERVADOS.contains(tld) || DOMINIOS_RESERVADOS.contains(dominio);
    }
}
