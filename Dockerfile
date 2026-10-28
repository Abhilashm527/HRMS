# Builds the HRMS API and runs it on a small JRE. Works on any Docker host (Render, Fly, a VM ...).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home hrms
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER hrms
# small free instances have about 512 MB: keep the heap modest
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
