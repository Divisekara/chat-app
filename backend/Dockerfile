FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies are resolved in their own layer, so a source-only change doesn't
# re-download the whole Maven repository on every build.
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# Don't run the JVM as root inside the container.
RUN useradd --system --no-create-home appuser
USER appuser
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]