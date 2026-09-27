FROM eclipse-temurin:25-jre

ARG SERVICE
WORKDIR /app
COPY ${SERVICE}/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
