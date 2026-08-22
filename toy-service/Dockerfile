# Pre-built jar (run `./mvnw package -DskipTests` first) copied straight in — this
# machine's network makes an in-container `mvn package` too slow/unreliable to be
# worth it when the host already has a warm ~/.m2 cache from this project's test runs.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --system --uid 1000 toyrental
COPY target/toy-service-0.0.1-SNAPSHOT.jar app.jar
USER toyrental
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
