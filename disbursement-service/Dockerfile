FROM gradle:8.10-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle :disbursement-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/disbursement-service/build/libs/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java","-jar","/app/app.jar"]
