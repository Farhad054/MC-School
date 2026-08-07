# --- Build stage: compile the Spring Boot jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the POM first so dependency resolution is cached when only source changes.
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# --- Runtime stage: small Alpine JRE image ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Run as a non-root user (defence in depth).
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
# Container health check hits the actuator endpoint (busybox wget on Alpine).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
