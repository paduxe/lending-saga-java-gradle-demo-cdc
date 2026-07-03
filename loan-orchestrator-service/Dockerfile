FROM gradle:8.10-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle :loan-orchestrator-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/loan-orchestrator-service/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
