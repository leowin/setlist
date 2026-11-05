# Build: docker build -t setlist-server .   (same file as /Dockerfile in the repo root, which Cloud Run picks up)
# Run:   docker run -p 8080:8080 -e STORE_DIR=/data -v setlist-data:/data setlist-relay
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# Checkouts from Windows lose the executable bit and may carry CRLF line endings.
# SETLIST_SERVER_ONLY: settings.gradle.kts leaves out the Android modules (no SDK in this image).
ENV SETLIST_SERVER_ONLY=1
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon -q :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/server"]
