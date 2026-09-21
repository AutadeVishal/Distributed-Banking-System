FROM maven:3.9.11-eclipse-temurin-25 AS build

ARG SERVICE
WORKDIR /workspace

COPY banking-events/pom.xml banking-events/pom.xml
COPY banking-events/src banking-events/src
RUN mvn -B -f banking-events/pom.xml install -DskipTests

COPY ${SERVICE}/pom.xml ${SERVICE}/pom.xml
COPY ${SERVICE}/src ${SERVICE}/src
RUN mvn -B -f ${SERVICE}/pom.xml package -DskipTests

FROM eclipse-temurin:25-jre

ARG SERVICE
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
