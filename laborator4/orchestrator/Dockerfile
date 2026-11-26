# build context = laborator3/ (so the seed script in infra/ is shared with the k8s setup)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY orchestrator/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY orchestrator/src ./src
COPY infra/seed.js ./src/main/resources/seed.js
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/orchestrator.jar app.jar
EXPOSE 8001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
