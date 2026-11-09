# syntax=docker/dockerfile:1.7
# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Gradle 캐시(/root/.gradle)를 BuildKit 캐시 마운트로 유지해 의존성·배포판을 매 빌드마다 받지 않는다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon -q dependencies --configuration runtimeClasspath

COPY src ./src
# 테스트는 CI에서 별도 실행 (.gitlab-ci.yml)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q bootJar -x test \
    && cp build/libs/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 spring

# 변경이 드문 레이어(의존성)부터 복사해 이미지 레이어 캐시를 살린다
COPY --from=build --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/application/ ./

USER spring
EXPOSE 8081
# 컨테이너 메모리 한도의 75%까지 힙 사용
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
