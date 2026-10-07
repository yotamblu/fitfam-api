# Build and run the API as a small container. Used by Render (see render.yaml).

# ---- build stage: compile and package the jar with JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q -DskipTests package \
    && cp target/fitfam-api-*.jar /build/app.jar

# ---- run stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
USER app
WORKDIR /app
COPY --from=build /build/app.jar app.jar

# Render sets PORT (the app reads it); all other settings come from environment variables, never from the image.
# JVM flags for a small (512 MB) container are set with JAVA_TOOL_OPTIONS in render.yaml.
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "app.jar"]
