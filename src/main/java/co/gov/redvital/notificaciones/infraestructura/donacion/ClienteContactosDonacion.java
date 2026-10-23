package co.gov.redvital.notificaciones.infraestructura.donacion;

import co.gov.redvital.notificaciones.aplicacion.ContactosPort;
import co.gov.redvital.notificaciones.aplicacion.FalloEntrega;
import co.gov.redvital.notificaciones.infraestructura.configuracion.PropiedadesNotificaciones;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pide el contacto de un donante a Donación en el momento de entregar
 * ({@code GET /internal/v1/donantes/{id}/contacto}), con un token de servicio
 * del cliente {@code notificaciones} que Identidad emite y que se reutiliza
 * hasta un minuto antes de vencer. Plazo de tres segundos por llamada.
 */
@Component
class ClienteContactosDonacion implements ContactosPort {

    private record TokenServicio(String valor, Instant vence) {
    }

    private final RestClient donacion;
    private final RestClient identidad;
    private final JsonMapper json;
    private final PropiedadesNotificaciones.Identidad credencial;
    private volatile TokenServicio token;

    ClienteContactosDonacion(PropiedadesNotificaciones propiedades, JsonMapper json) {
        this.json = json;
        this.credencial = propiedades.identidad();
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(propiedades.plazo());
        fabrica.setReadTimeout(propiedades.plazo());
        this.donacion = RestClient.builder().baseUrl(propiedades.donacion().url()).requestFactory(fabrica).build();
        this.identidad = RestClient.builder().baseUrl(credencial.url()).requestFactory(fabrica).build();
    }

    @Override
    public Contacto deDonante(UUID donanteId, String tipoAviso) {
        String portador = tokenServicio();
        try {
            String respuesta = donacion.get()
                    .uri("/internal/v1/donantes/{id}/contacto?tipo={tipo}", donanteId, tipoAviso)
                    .header("Authorization", "Bearer " + portador)
                    .retrieve()
                    .body(String.class);
            JsonNode nodo = json.readTree(respuesta);
            if (!nodo.path("contactable").asBoolean(false)) {
                return new Contacto(false, null, null);
            }
            return new Contacto(true, nodo.path("correo").asString(), nodo.path("nombre").asString(""));
        } catch (RestClientResponseException e) {
            int estado = e.getStatusCode().value();
            if (estado == 404) {
                // El evento nombra a un donante que no existe, o el trabajador no está autorizado: un defecto.
                throw new FalloEntrega.AvisoInvalido("destinatario_inexistente",
                        "Donación no reconoce al destinatario del aviso (404).");
            }
            if (estado == 400) {
                throw new FalloEntrega.AvisoInvalido("evento_mal_formado", "Donación rechazó el tipo de aviso (400).");
            }
            if (estado == 401) {
                token = null; // se pide uno nuevo en el siguiente intento
            }
            throw new FalloEntrega.CanalNoDisponible("Donación respondió " + estado + " al pedir el contacto.", e);
        } catch (RestClientException e) {
            throw new FalloEntrega.CanalNoDisponible("Donación no responde: " + e.getMessage(), e);
        }
    }

    private String tokenServicio() {
        TokenServicio actual = token;
        if (actual != null && Instant.now().isBefore(actual.vence())) {
            return actual.valor();
        }
        if (credencial.secreto() == null || credencial.secreto().isBlank()) {
            throw new FalloEntrega.CanalNoDisponible("Falta el secreto del cliente notificaciones ante Identidad.", null);
        }
        String respuesta;
        try {
            respuesta = identidad.post().uri("/internal/v1/sesiones/servicio")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(Map.of("cliente", credencial.cliente(), "secreto", credencial.secreto())))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new FalloEntrega.CanalNoDisponible("Identidad no emitió el token de servicio: " + e.getMessage(), e);
        }
        JsonNode nodo = json.readTree(respuesta);
        long segundos = nodo.path("expira_en").asLong(900);
        TokenServicio nuevo = new TokenServicio(nodo.path("token_acceso").asString(),
                Instant.now().plusSeconds(Math.max(segundos - 60, 30)));
        token = nuevo;
        return nuevo.valor();
    }
}
