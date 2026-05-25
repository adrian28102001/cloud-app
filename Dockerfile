# syntax=docker/dockerfile:1.6

FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts ./
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle src ./src
RUN gradle bootJar --no-daemon -x test

FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
