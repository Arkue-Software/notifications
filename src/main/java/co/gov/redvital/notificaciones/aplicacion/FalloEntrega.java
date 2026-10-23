package co.gov.redvital.notificaciones.aplicacion;

/**
 * Un intento de entrega que no terminó (DD, Tabla 17). Cada clase de fallo
 * tiene su tratamiento en el consumidor:
 * <ul>
 *   <li>{@link CanalNoDisponible}: se reintenta el mismo aviso sin avanzar.</li>
 *   <li>{@link Rechazo}: temas de reintento y, agotados, mensajes muertos.</li>
 *   <li>{@link AvisoInvalido}: directo a mensajes muertos; reintentar no lo arregla.</li>
 * </ul>
 */
public abstract sealed class FalloEntrega extends RuntimeException
        permits FalloEntrega.CanalNoDisponible, FalloEntrega.Rechazo, FalloEntrega.AvisoInvalido {

    private final String codigo;

    protected FalloEntrega(String codigo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.codigo = codigo;
    }

    /** Código del resultado: va a la métrica y a la cabecera del mensaje muerto. */
    public String codigo() {
        return codigo;
    }

    /**
     * El proveedor de correo, Donación o Identidad no responden. No es culpa
     * del destinatario y no consume intentos.
     */
    public static final class CanalNoDisponible extends FalloEntrega {

        public CanalNoDisponible(String mensaje, Throwable causa) {
            super("canal_no_disponible", mensaje, causa);
        }
    }

    /** El proveedor rechazó el mensaje por el destinatario o por el mensaje mismo. */
    public static final class Rechazo extends FalloEntrega {

        public static final String DESTINATARIO = "destinatario_rechazado";
        public static final String PROVEEDOR = "rechazo_proveedor";

        public Rechazo(String codigo, String mensaje, Throwable causa) {
            super(codigo, mensaje, causa);
        }
    }

    /** El aviso no cumple el contrato o nombra a un destinatario que no existe: un defecto. */
    public static final class AvisoInvalido extends FalloEntrega {

        public AvisoInvalido(String codigo, String mensaje) {
            super(codigo, mensaje, null);
        }
    }
}
