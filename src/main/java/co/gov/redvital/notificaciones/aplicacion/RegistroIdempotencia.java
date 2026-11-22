package co.gov.redvital.notificaciones.aplicacion;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RegistroIdempotencia(String claveIdempotencia, String eventoOrigen, UUID avisoId,
        Map<String, String> resultado) {

    public RegistroIdempotencia {
        Objects.requireNonNull(claveIdempotencia, "claveIdempotencia");
        Objects.requireNonNull(eventoOrigen, "eventoOrigen");
        Objects.requireNonNull(resultado, "resultado");
        if (claveIdempotencia.isBlank() || claveIdempotencia.length() > 255) {
            throw new IllegalArgumentException("La clave de idempotencia debe tener entre 1 y 255 caracteres.");
        }
        if (eventoOrigen.isBlank() || eventoOrigen.length() > 100) {
            throw new IllegalArgumentException("El evento de origen debe tener entre 1 y 100 caracteres.");
        }
        resultado = Map.copyOf(resultado);
    }
}
