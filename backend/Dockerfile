# --- Build stage: compile the Spring Boot jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the POM first so dependency resolution is cached when only source changes.
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# --- Runtime stage: small JRE image ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Container health check hits the actuator endpoint.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
