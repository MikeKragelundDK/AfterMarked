# syntax=docker/dockerfile:1
#
# Runtime-image. Forventer en faerdigbygget jar i target/ — byg foerst:
#   ./mvnw -B package
# CI bygger jar'en én gang og genbruger den her, saa Maven-afhaengigheder ikke
# hentes igen inde i Docker.
#
# Basisimage pinnet paa digest (ingen version drift): eclipse-temurin:25-jre-alpine.

FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682 AS extract
WORKDIR /build
COPY target/*.jar application.jar
# Splitter jar'en i lag (afhaengigheder, loader, snapshot-afhaengigheder, app),
# saa kun det oeverste lag aendrer sig ved et almindeligt deploy.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
# Basisimagets OS-pakker opdateres, saa kendte CVE'er med fix i Alpine ikke
# venter paa naeste temurin-tag. Trivy-gaten i CI fangede praecis det (openssl).
RUN apk upgrade --no-cache \
    && addgroup -S theticket && adduser -S -G theticket theticket
WORKDIR /application
COPY --from=extract --chown=theticket:theticket /build/extracted/dependencies/ ./
COPY --from=extract --chown=theticket:theticket /build/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=theticket:theticket /build/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=theticket:theticket /build/extracted/application/ ./
USER theticket
EXPOSE 8080 8081

# Readiness — den samme, compose og load balanceren bruger. I imaget, saa et
# bart `docker run` ogsaa kan se, om processen er klar til trafik.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health/readiness | grep -q UP || exit 1

# SIGTERM er default, men skrives eksplicit: den er kontrakten bag
# server.shutdown=graceful. Java er PID 1 (exec-form ENTRYPOINT) og modtager
# signalet direkte — ingen shell imellem, der ville sluge det.
STOPSIGNAL SIGTERM

# JVM-flagene staar i ENTRYPOINT, ikke i JAVA_TOOL_OPTIONS: den variabel faar
# JVM'en til at skrive "Picked up JAVA_TOOL_OPTIONS: ..." paa stderr ved hver
# opstart, og den linje er ikke JSON. I prod-profilen er stdout/stderr ECS-JSON
# (logging.structured.format.console), saa log-indsamleren ville faa én
# uparsebar linje pr. containerstart. Ops kan stadig saette JAVA_TOOL_OPTIONS
# udefra for at tilfoeje flag ad hoc.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
