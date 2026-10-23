package co.gov.redvital.notificaciones.aplicacion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Una plantilla por tipo de aviso (DD, 9.3). Cada mensaje sale en texto plano
 * y en HTML, armados con la misma estructura para que digan lo mismo. Nunca
 * llevan documento, teléfono, grupo sanguíneo ni contraseña; los enlaces son al
 * sitio del ambiente y no hay imágenes remotas.
 */
public final class Plantillas {

    private static final Locale ESPANOL = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, h:mm a", ESPANOL);
    private static final Set<String> TIPOS = Set.of(Aviso.CUENTA_CREADA, Aviso.CAMPANIA_PUBLICADA);
    private static final String AUTOMATICO = "Este es un mensaje automático de RedVital; no lo respondas.";

    /** Estructura común de un mensaje, de la que salen el texto y el HTML. */
    record Cuerpo(String asunto, String titulo, String saludo, List<String> parrafos, String boton, String enlace,
                  List<String> despuesDelBoton, String tituloLista, List<String> lista, List<String> cierre) {
    }

    private final String sitio;
    private final ZoneId zona;

    public Plantillas(String sitio, ZoneId zona) {
        this.sitio = sitio.endsWith("/") ? sitio.substring(0, sitio.length() - 1) : sitio;
        this.zona = zona;
    }

    public boolean soporta(String tipo) {
        return TIPOS.contains(tipo);
    }

    public CanalCorreoPort.Mensaje componer(Aviso aviso, String correo, String nombre) {
        String saludo = nombre == null || nombre.isBlank() ? "Hola:" : "Hola, " + nombre.trim() + ":";
        Cuerpo cuerpo = switch (aviso.tipo()) {
            case Aviso.CUENTA_CREADA -> cuentaCreada(saludo);
            case Aviso.CAMPANIA_PUBLICADA -> campaniaPublicada(saludo, aviso.plantilla());
            default -> throw new FalloEntrega.AvisoInvalido("tipo_sin_plantilla",
                    "No hay plantilla para el tipo " + aviso.tipo() + ".");
        };
        return new CanalCorreoPort.Mensaje("<aviso-" + aviso.avisoId() + "@redvital>", correo, cuerpo.asunto(),
                texto(cuerpo), html(cuerpo));
    }

    private Cuerpo cuentaCreada(String saludo) {
        return new Cuerpo(
                "Tu cuenta de donante en RedVital está lista",
                "Tu cuenta está lista",
                saludo,
                List.of("Tu cuenta de donante en RedVital quedó creada. Gracias por sumarte a la red de donantes de sangre."),
                "Entrar a RedVital", sitio + "/ingresar",
                List.of("Entra con el correo y la contraseña que registraste."),
                "En tu cuenta puedes:",
                List.of("Ver si ya puedes donar y desde qué fecha.",
                        "Consultar tu historial de donaciones y tus reconocimientos.",
                        "Actualizar tus datos de contacto y tus autorizaciones."),
                List.of("¿Quieres donar pronto? Busca una jornada cerca de ti en " + sitio + "/jornadas.",
                        "Si no creaste esta cuenta, ignora este mensaje: sin la contraseña nadie puede entrar.",
                        AUTOMATICO));
    }

    private Cuerpo campaniaPublicada(String saludo, Map<String, String> datos) {
        List<String> parrafos = new ArrayList<>();
        String nombre = datos.get("nombre");
        if (nombre != null) {
            parrafos.add("Se publicó la jornada «" + nombre + "»" + (datos.get("sede") == null ? "." : ", en " + datos.get("sede") + "."));
            String inicia = fecha(datos.get("inicia_en"));
            String termina = fecha(datos.get("termina_en"));
            if (inicia != null && termina != null) {
                parrafos.add("Va desde el " + inicia + " hasta el " + termina + ".");
            }
        } else {
            parrafos.add("Se publicó una jornada de donación de sangre cerca de ti.");
        }
        return new Cuerpo(
                nombre == null ? "Hay una nueva jornada de donación cerca de ti" : "Nueva jornada de donación: " + nombre,
                "Hay una jornada de donación cerca de ti",
                saludo,
                parrafos,
                "Ver las jornadas", sitio + "/jornadas",
                List.of(),
                null, List.of(),
                List.of("Recibes este aviso porque autorizaste los avisos de campañas. Puedes retirar esa autorización en tu perfil: "
                                + sitio + "/donantes/perfil.",
                        AUTOMATICO));
    }

    /** Fecha y hora en la zona del ambiente; nula si el dato no es una fecha válida. */
    private String fecha(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return FECHA_HORA.format(Instant.parse(valor).atZone(zona));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String texto(Cuerpo c) {
        var t = new StringBuilder(c.saludo()).append("\n\n");
        c.parrafos().forEach(p -> t.append(p).append("\n\n"));
        t.append(c.boton()).append(": ").append(c.enlace()).append("\n");
        c.despuesDelBoton().forEach(p -> t.append(p).append("\n"));
        t.append("\n");
        if (c.tituloLista() != null) {
            t.append(c.tituloLista()).append("\n");
            c.lista().forEach(e -> t.append("  - ").append(e).append("\n"));
            t.append("\n");
        }
        c.cierre().forEach(p -> t.append(p).append("\n\n"));
        return t.append("— RedVital, plataforma nacional de donación de sangre\n").toString();
    }

    static String html(Cuerpo c) {
        var h = new StringBuilder("""
                <!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">\
                <title>%s</title></head>\
                <body style="margin:0;padding:0;background:#fdfaf8;font-family:'Segoe UI',Arial,sans-serif;color:#3a3a3a">\
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#fdfaf8;padding:24px 12px">\
                <tr><td align="center">\
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="max-width:560px;background:#ffffff;border:1px solid #f5e8e8;border-radius:16px">\
                <tr><td style="padding:32px 28px">\
                <p style="margin:0 0 24px;font-size:20px;font-weight:700;color:#8b1a24">RedVital</p>\
                <h1 style="margin:0 0 20px;font-size:24px;line-height:1.25;color:#571017">%s</h1>\
                <p style="margin:0 0 16px;font-size:16px;line-height:1.5">%s</p>"""
                .formatted(escapar(c.asunto()), escapar(c.titulo()), escapar(c.saludo())));
        c.parrafos().forEach(p -> h.append(parrafo(p)));
        h.append("<p style=\"margin:24px 0 8px\"><a href=\"").append(escapar(c.enlace()))
                .append("\" style=\"display:inline-block;background:#8b1a24;color:#ffffff;text-decoration:none;"
                        + "font-weight:700;padding:12px 24px;border-radius:999px\">")
                .append(escapar(c.boton())).append("</a></p>");
        c.despuesDelBoton().forEach(p -> h.append("<p style=\"margin:0 0 16px;font-size:14px;color:#5a5a5a\">")
                .append(escapar(p)).append("</p>"));
        if (c.tituloLista() != null) {
            h.append(parrafo(c.tituloLista())).append("<ul style=\"margin:0 0 16px;padding-left:20px;font-size:16px;line-height:1.6\">");
            c.lista().forEach(e -> h.append("<li>").append(escapar(e)).append("</li>"));
            h.append("</ul>");
        }
        c.cierre().forEach(p -> h.append("<p style=\"margin:16px 0 0;font-size:13px;line-height:1.5;color:#857c7c\">")
                .append(escapar(p)).append("</p>"));
        return h.append("<p style=\"margin:24px 0 0;font-size:13px;color:#857c7c\">RedVital, plataforma nacional de "
                + "donación de sangre</p></td></tr></table></td></tr></table></body></html>").toString();
    }

    private static String parrafo(String p) {
        return "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.5\">" + escapar(p) + "</p>";
    }

    static String escapar(String valor) {
        return valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
