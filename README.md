# Trabajador de Notificaciones (RedVital)

Consume EV-02 `notice.requested` (tema `redvital.donacion.notice-requested.v1`, grupo `notificaciones`) y entrega cada aviso por correo (DD V3.0, Sección 9). No tiene base de datos ni rutas publicadas.

## Ciclo de un aviso

1. Lee el evento. Trae identificadores y datos no personales de la plantilla, nunca el correo.
2. Pide el contacto a Donación (`GET /internal/v1/donantes/{id}/contacto?tipo=…`), con un token de servicio del cliente `notificaciones` que emite Identidad.
3. Compone el correo con la plantilla del tipo, en texto y HTML, con los enlaces al sitio (`SITIO_URL`).
4. Lo envía por SMTP. El Message-ID es `<aviso-{aviso_id}@redvital>`.

Tipos con plantilla: `cuenta_creada` (confirmación del registro de un donante) y `campania_publicada`.

## Resultados (DD, Tabla 17)

| Resultado | Tratamiento |
|---|---|
| `entregado`, `destinatario_sin_contacto`, `dominio_reservado` | Terminales: se confirma la posición. `dominio_reservado` evita enviar a `.test`, `.example`, `.invalid` y `.localhost` (las cuentas de prueba de QA). |
| `canal_no_disponible` | Gmail, Donación o Identidad no responden: se reintenta el mismo aviso de 15 s a 5 min, sin límite y sin avanzar. |
| `destinatario_rechazado`, `rechazo_proveedor` | Temas `….notificaciones.retry-0` a `-5`, ocho intentos en total, y luego `….notificaciones.dlt`. |
| Aviso inválido (`evento_mal_formado`, `destinatario_inexistente`, `tipo_sin_plantilla`) | Directo a `….notificaciones.dlt`. |

Lo que va a mensajes muertos lleva la cabecera `aviso-resultado` y sube `redvital_eventos_mensajes_muertos_total`. Cada intento suma a `redvital_avisos_total{tipo,resultado}`.

## Configuración

| Variable o secreto | Para qué |
|---|---|
| `SMTP_USUARIO` | Cuenta de correo del proyecto (en QA, la de Gmail) |
| `/run/secrets/notificaciones_smtp_clave` | Contraseña de aplicación de esa cuenta (se aceptan los espacios con que la muestra Gmail) |
| `SMTP_HOST`, `SMTP_PUERTO`, `SMTP_STARTTLS` | Por defecto `smtp.gmail.com`, `587`, `true` |
| `SITIO_URL` | Sitio del ambiente, para los enlaces (p. ej. `https://10.43.98.220`) |
| `/run/secrets/notificaciones_secreto_identidad` | Credencial del cliente `notificaciones` ante Identidad |
| `/run/secrets/kafka_notificaciones` | Usuario SCRAM `notificaciones` |

## Pruebas

```bash
mvn -B verify
```

Las de integración levantan Kafka con Testcontainers y un servidor SMTP en memoria (GreenMail); en este equipo se corren con `Despliegue_RedVital/pruebas/correr-pruebas.sh notificaciones`.
