import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Comprobación de salud del contenedor. La imagen distroless no tiene shell
 * ni curl, así que la consulta la hace este programa con el JRE de la imagen.
 */
public final class Salud {

    public static void main(String[] argumentos) {
        try {
            HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(argumentos[0]))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            int estado = cliente.send(peticion, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(estado == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
