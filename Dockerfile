# Build: docker build -t setlist-server .   (same file as /Dockerfile in the repo root, which Cloud Run picks up)
# Run:   docker run -p 8080:8080 -e STORE_DIR=/data -v setlist-data:/data setlist-relay
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# chmod: checkouts from Windows lose the executable bit.
RUN chmod +x gradlew && ./gradlew --no-daemon -q :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/server"]
