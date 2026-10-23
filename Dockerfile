# syntax=docker/dockerfile:1.7
# RedVital — Trabajador de Notificaciones. Herramientas V4.0: construcción con
# Temurin 25 y ejecución con un JRE reducido por jlink sobre distroless, sin
# root (entrada 81). Igual que Donación.

FROM maven:3.9-eclipse-temurin-25 AS construccion
WORKDIR /fuente
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/redvital-notificaciones-worker.jar extract --destination /ext

FROM eclipse-temurin:25-jdk AS jre
COPY --from=construccion /ext /ext
# Módulos que pide el código, más los que se cargan por reflexión o por
# proveedor de servicios: TLS (STARTTLS de Gmail), SCRAM de Kafka y métricas.
RUN MODULOS="$(jdeps --ignore-missing-deps -q --recursive --multi-release 25 --print-module-deps \
        --class-path '/ext/lib/*' /ext/*.jar 2>/dev/null || echo java.base)" \
 && jlink --add-modules "${MODULOS},java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,jdk.charsets,jdk.crypto.ec,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs" \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre
COPY docker/Salud.java /salud/Salud.java
RUN javac -d /salud /salud/Salud.java

FROM gcr.io/distroless/java-base-debian13:nonroot
COPY --from=jre /jre /opt/java
COPY --from=jre /salud /app/salud
COPY --from=construccion /ext /app
WORKDIR /app
ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8081
USER nonroot
HEALTHCHECK --interval=15s --timeout=6s --start-period=90s --retries=5 \
  CMD ["/opt/java/bin/java", "-XX:TieredStopAtLevel=1", "-cp", "/app/salud", "Salud", "http://127.0.0.1:8081/actuator/health/readiness"]
ENTRYPOINT ["/opt/java/bin/java", "-jar", "/app/redvital-notificaciones-worker.jar"]
