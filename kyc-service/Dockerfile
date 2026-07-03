FROM gradle:8.10-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle :kyc-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/kyc-service/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]
