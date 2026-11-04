# Band-Relay-Server (Phase 6)

Schlanker WebSocket-Relay für Bands, die nicht im selben WLAN sind: Räume mit Code, Weiterleitung der
Band-Nachrichten (Host → alle, Mitglieder → Host), Uhr-Referenz (Ping/Pong mit Server-Zeitstempeln)
und die zuletzt verteilte Setlist je Raum (optional dauerhaft in `STORE_DIR`).

```
./gradlew :server:run                      # lokal auf Port 8080
docker build -t setlist-relay -f server/Dockerfile . && docker run -p 8080:8080 setlist-relay
```

Google Cloud Run: `gcloud run deploy setlist-relay --source . --region europe-west1 --allow-unauthenticated`
(Dockerfile im Repo-Root angeben oder `server/Dockerfile` kopieren). In der App unter „Band → Über
Internet“ die Adresse eintragen, z. B. `wss://setlist-relay-xyz.a.run.app/ws`.

Hinweis zur Genauigkeit: Über das Internet hängt die Synchronität von der Laufzeit-Symmetrie ab; im
selben Raum bleibt WLAN-Sync genauer. Für gemeinsame Playlists und Proben an getrennten Orten ist der
Relay gedacht.
