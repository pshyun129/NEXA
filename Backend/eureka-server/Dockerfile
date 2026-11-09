FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN bash gradlew --no-daemon bootJar \
    && cp build/libs/*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 spring

COPY --from=build /app/app.jar ./app.jar

USER spring

EXPOSE 8761

ENTRYPOINT ["java", "-jar", "/app/app.jar"]