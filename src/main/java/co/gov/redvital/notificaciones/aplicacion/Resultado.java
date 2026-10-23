package co.gov.redvital.notificaciones.aplicacion;

/**
 * Resultados terminales de una entrega (DD, Tabla 17). Los demás —canal no
 * disponible, rechazos y avisos inválidos— son excepciones de {@link FalloEntrega}.
 */
public enum Resultado {
    ENTREGADO("entregado"),
    /** Donación responde que el donante no es contactable: no es un fallo, no se reintenta. */
    DESTINATARIO_SIN_CONTACTO("destinatario_sin_contacto"),
    /**
     * El correo es de un dominio reservado que no recibe correo (.test,
     * .example, .invalid, .localhost): las cuentas sintéticas y las pruebas de
     * QA. Enviarlo solo produciría rebotes en el buzón del proyecto.
     */
    DOMINIO_RESERVADO("dominio_reservado");

    private final String codigo;

    Resultado(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
