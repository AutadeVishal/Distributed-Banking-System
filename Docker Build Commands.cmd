mvn -f banking-common/pom.xml clean install -DskipTests

mvn -f account-service/pom.xml package -DskipTests
mvn -f transaction-service/pom.xml package -DskipTests
mvn -f payment-service/pom.xml package -DskipTests
mvn -f fraud-detection-service/pom.xml package -DskipTests
mvn -f notification-service/pom.xml package -DskipTests
mvn -f api-gateway/pom.xml package -DskipTests

docker compose build
docker compose up