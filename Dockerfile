# syntax=docker/dockerfile:1

# --- Build stage: compile and lay out the app with Gradle's installDist (lib/*.jar + bin/ start script).
# Tests are not run here; CI runs them before the image is built.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# The cache mount keeps downloaded dependencies between builds without baking them into a layer.
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon

# --- Runtime stage: JRE only, no build tools or sources, running as a non-root user.
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/build/install/payment-service/ ./
USER app
# The JVM is container-aware but by default uses only 25% of the memory limit for the heap.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
# The start script ends with `exec java ...`, so the JVM is PID 1 and receives SIGTERM from `docker stop`.
ENTRYPOINT ["bin/payment-service"]
