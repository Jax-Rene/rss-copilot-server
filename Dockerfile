FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY docker docker
COPY src src
RUN ./mvnw -q clean package -DskipTests
RUN javac -d target/docker-healthcheck docker/Healthcheck.java

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV RSS_COPILOT_DB_PATH=/data/rss-copilot.db
VOLUME ["/data"]

COPY --from=build /app/target/docker-healthcheck /app/healthcheck
COPY --from=build /app/target/rss-copilot-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["java", "-cp", "/app/healthcheck", "Healthcheck"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
