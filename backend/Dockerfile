# Produkční obraz backendu (docs/vydani.md — "Neexistuje produkční backend"). Testy se tu
# záměrně nepouští (ApplicationSmokeTest potřebuje Docker/Testcontainers, což by znamenalo
# Docker-in-Docker) — testy patří do CI, ne do buildu image.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Commit, ze kterého se obraz staví (docs/vydani.md, "Verzování a vydání") — compose.prod.yaml
# ho předává jako build arg, `springBoot { buildInfo() }` (build.gradle) ho čte přes
# System.getenv('GIT_SHA') a promítne do /actuator/info. Prázdný default nerozbije build, když
# se GIT_SHA nepředá (lokální `docker build` bez skriptu z docs/nasazeni.md).
ARG GIT_SHA=""
ENV GIT_SHA=${GIT_SHA}

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --create-home --home-dir /app appuser \
    && mkdir -p /data/media \
    && chown -R appuser:appuser /app /data/media

COPY --from=build /app/build/libs/*.jar app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
