package co.gov.redvital.notificaciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EntregarAvisoTest {

    private static final Plantillas PLANTILLAS = new Plantillas("https://qa.redvital.test/", ZoneId.of("America/Bogota"));

    private final List<CanalCorreoPort.Mensaje> enviados = new ArrayList<>();

    private EntregarAviso conContacto(ContactosPort.Contacto contacto) {
        return new EntregarAviso((id, tipo) -> contacto, PLANTILLAS, enviados::add);
    }

    private static Aviso aviso(String tipo, Map<String, String> plantilla) {
        return new Aviso(UUID.randomUUID(), tipo, "donante", UUID.randomUUID(), null, plantilla);
    }

    @Nested
    class Entrega {

        @Test
        void la_confirmacion_de_la_cuenta_lleva_el_saludo_el_enlace_al_sitio_y_lo_que_puede_hacer() {
            Aviso aviso = aviso(Aviso.CUENTA_CREADA, Map.of());
            var resultado = conContacto(new ContactosPort.Contacto(true, "laura@correo.co", "Laura Pérez")).ejecutar(aviso);

            assertThat(resultado).isEqualTo(Resultado.ENTREGADO);
            assertThat(enviados).hasSize(1);
            var m = enviados.getFirst();
            assertThat(m.destinatario()).isEqualTo("laura@correo.co");
            assertThat(m.idMensaje()).isEqualTo("<aviso-" + aviso.avisoId() + "@redvital>");
            assertThat(m.asunto()).isEqualTo("Tu cuenta de donante en RedVital está lista");
            assertThat(m.texto()).contains("Hola, Laura Pérez:", "https://qa.redvital.test/ingresar",
                    "https://qa.redvital.test/jornadas", "Ver si ya puedes donar", "Si no creaste esta cuenta");
            assertThat(m.html()).contains("href=\"https://qa.redvital.test/ingresar\"", "Entrar a RedVital", "Laura Pérez");
        }

        @Test
        void el_aviso_de_campania_muestra_la_jornada_y_sus_fechas_en_hora_de_Bogota() {
            Aviso aviso = aviso(Aviso.CAMPANIA_PUBLICADA, Map.of("nombre", "Jornada Ciudad Universitaria",
                    "sede", "Bloque 22", "inicia_en", "2026-10-20T13:00:00Z", "termina_en", "2026-10-26T22:00:00Z"));
            conContacto(new ContactosPort.Contacto(true, "laura@correo.co", "Laura")).ejecutar(aviso);

            var m = enviados.getFirst();
            assertThat(m.asunto()).isEqualTo("Nueva jornada de donación: Jornada Ciudad Universitaria");
            assertThat(m.texto()).contains("«Jornada Ciudad Universitaria», en Bloque 22.",
                    "20 de octubre de 2026, 8:00", "26 de octubre de 2026, 5:00", "https://qa.redvital.test/donantes/perfil");
        }

        @Test
        void un_aviso_de_campania_sin_plantilla_sale_igual_con_un_texto_general() {
            conContacto(new ContactosPort.Contacto(true, "laura@correo.co", "Laura")).ejecutar(aviso(Aviso.CAMPANIA_PUBLICADA, null));
            assertThat(enviados.getFirst().asunto()).isEqualTo("Hay una nueva jornada de donación cerca de ti");
        }

        @Test
        void el_html_escapa_lo_que_viene_de_los_datos() {
            conContacto(new ContactosPort.Contacto(true, "a@correo.co", "<b>Ana</b> & \"Co\"")).ejecutar(aviso(Aviso.CUENTA_CREADA, Map.of()));
            assertThat(enviados.getFirst().html()).contains("&lt;b&gt;Ana&lt;/b&gt; &amp; &quot;Co&quot;").doesNotContain("<b>Ana");
        }
    }

    @Nested
    class SinEnvio {

        @Test
        void un_donante_no_contactable_es_un_resultado_terminal_sin_correo() {
            var resultado = conContacto(new ContactosPort.Contacto(false, null, null)).ejecutar(aviso(Aviso.CUENTA_CREADA, Map.of()));
            assertThat(resultado).isEqualTo(Resultado.DESTINATARIO_SIN_CONTACTO);
            assertThat(enviados).isEmpty();
        }

        @Test
        void a_los_dominios_reservados_nunca_se_envia() {
            for (String correo : List.of("donante.1@redvital.test", "x@example.com", "y@algo.invalid", "z@sub.example")) {
                var resultado = conContacto(new ContactosPort.Contacto(true, correo, "N")).ejecutar(aviso(Aviso.CUENTA_CREADA, Map.of()));
                assertThat(resultado).as(correo).isEqualTo(Resultado.DOMINIO_RESERVADO);
            }
            assertThat(enviados).isEmpty();
            assertThat(EntregarAviso.reservado("laura@javeriana.edu.co")).isFalse();
            assertThat(EntregarAviso.reservado("laura@testing.com")).isFalse();
        }

        @Test
        void un_tipo_sin_plantilla_o_un_destinatario_institucional_es_un_defecto() {
            var entregar = conContacto(new ContactosPort.Contacto(true, "a@correo.co", "A"));
            assertThatThrownBy(() -> entregar.ejecutar(aviso("convocatoria", Map.of())))
                    .isInstanceOfSatisfying(FalloEntrega.AvisoInvalido.class, f -> assertThat(f.codigo()).isEqualTo("tipo_sin_plantilla"));
            assertThatThrownBy(() -> entregar.ejecutar(new Aviso(UUID.randomUUID(), Aviso.CUENTA_CREADA, "institucion",
                    UUID.randomUUID(), null, Map.of())))
                    .isInstanceOfSatisfying(FalloEntrega.AvisoInvalido.class, f -> assertThat(f.codigo()).isEqualTo("destinatario_no_soportado"));
            assertThat(enviados).isEmpty();
        }
    }
}
