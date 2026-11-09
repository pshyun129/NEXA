FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN bash gradlew --no-daemon bootJar \
    && cp build/libs/*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home spring

COPY --from=build /app/app.jar ./app.jar

USER spring

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "/app/app.jar"]