# One image definition for both Java deployables: build with --build-arg APP=ingestion-app or APP=timescale-sink.
# Build context is the repo root, because events-common packages the shared schemas from schemas/.

FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
ARG APP
WORKDIR /src
COPY schemas/ schemas/
COPY services/ingestion/ services/ingestion/
WORKDIR /src/services/ingestion
# Tests run in CI, not in the image build. The Gradle cache persists between builds; sharing=locked
# because Compose builds both apps in parallel and two Gradles can't share one cache directory.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain ":${APP}:bootJar" -x test
# Split the jar into layers (dependencies change rarely, application code often) so image
# rebuilds and pulls only move the layers that changed.
RUN java -Djarmode=tools -jar "${APP}/build/libs/${APP}.jar" extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.12_8-jre-noble
RUN useradd --system --uid 10001 --no-create-home app
USER app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
