# Container image for the HTTP API (e.g. for Cloud Run).
#   docker build -t incident-triage .
#   docker run -p 8080:8080 -e TRIAGE_MODEL=baseline incident-triage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY evals ./evals
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/lib ./lib
COPY --from=build /src/target/incident-triage-agent.jar ./app.jar
# Cloud Run injects PORT; AppConfig reads it.
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar", "serve"]
