
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY backend/ledgerline/pom.xml .
COPY backend/ledgerline/src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
