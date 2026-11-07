# Band-Server

Ein kleiner Ktor-Server, über den sich die Handys einer Band abgleichen – **ohne dass der Server die
Daten lesen kann**. Lieder, Noten und Setlists bleiben auf den Handys; der Server ist nur Austauschpunkt,
Link-Ablage und gemeinsame Uhr.

## Was er macht

| Weg | Zweck | Was der Server sieht |
|---|---|---|
| `PUT /rooms/{id}` | Raum einer Band anlegen | Raum-ID (HMAC des Band-Schlüssels) + Prüfschlüssel |
| `GET/PUT /rooms/{id}/manifest` | Inhaltsverzeichnis der Band (ETag/If-Match gegen Konflikte) | verschlüsselter Blob, Versionsnummer |
| `GET/PUT /rooms/{id}/blobs/{sha256}` | Lieder/Noten/Setlists/Audiospuren (bis 30 MB), benannt nach dem Hash des Chiffrats | verschlüsselte Blobs |
| `DELETE /rooms/{id}` | Band löscht ihren Raum | – |
| `POST /shares?ttl=7\|30\|90`, `GET /shares/{id}/blob` | Teilen-Links | verschlüsselter Blob, Ablaufzeit |
| `GET /s/{id}` | Vorschau-Seite: entschlüsselt im Browser mit dem Schlüssel aus dem `#`-Teil des Links | nur die Anfrage |
| `GET /join` | Einladungsseite („In der App öffnen“); Band-Schlüssel steht nur hinter `#` | nur die Anfrage |
| `/ws` | Live-Raum: Start/Stopp/Weiterblättern, Uhr (Ping/Pong), `Changed`-Hinweis bei neuem Manifest | Nachrichten im Durchlauf, nichts gespeichert |
| `/.well-known/assetlinks.json`, `/.well-known/apple-app-site-association` | App-Links | – |
| `/privacy` | Was gespeichert wird | – |

**Autorisierung** ohne Konten: Jede Band hat einen zufälligen 32-Byte-Schlüssel, der nur auf den Handys liegt
(Einladung per QR/Link). Daraus leitet die App ab: die Raum-ID (`HMAC(key,"room")`), einen Prüfschlüssel
(`HKDF(key,"auth")`), der beim Anlegen des Raums einmal registriert wird, und den Datenschlüssel
(`HKDF(key,"data")`), den der Server nie sieht. Anfragen tragen `X-Band-Auth: <unixzeit>:<HMAC(prüfschlüssel,
zeit\nMETHODE\npfad\nsha256(body))>` (±5 min). Der WebSocket-`Join` ist genauso signiert.

**Verschlüsselung**: AES-256-CTR + HMAC-SHA256 (Encrypt-then-MAC), Schlüssel per HKDF getrennt; identisch in
Kotlin (Android: `javax.crypto`, iOS: CommonCrypto) und in der Browser-Vorschau (WebCrypto).

**Aufräumen**: Räume ohne Zugriff seit `ROOM_TTL_DAYS` (90) und abgelaufene Teilen-Links werden stündlich
gelöscht. Die Handys laden ihre Daten beim nächsten Abgleich einfach wieder hoch.

## Lokal

```
./gradlew :server:run                                # Port 8080, Daten in ./data
./gradlew :server:test
docker build -t setlist-server . && docker run -p 8080:8080 -v setlist-data:/data -e STORE_DIR=/data setlist-server
```

Umgebung: `PORT`, `STORE_DIR`, `ROOM_TTL_DAYS`, `ANDROID_PACKAGE`, `ANDROID_SHA256` (Fingerprints, mit Komma),
`IOS_APP_ID` (`<TeamID>.at.zentriert.setlist`).

## Google Cloud Run

```
PROJECT=mein-projekt ANDROID_SHA256=AA:BB:… ./server/deploy/cloudrun.sh
```

Beim ersten Deploy fehlen in neuen Projekten meist noch Rechte für das Build-Dienstkonto
(`<projektnummer>-compute@developer.gserviceaccount.com`): Rollen `roles/cloudbuild.builds.builder`,
`roles/storage.objectViewer` und `roles/artifactregistry.writer` vergeben (`gcloud projects add-iam-policy-binding …`).
Das `Dockerfile` im Repo-Root (Kopie von `server/Dockerfile`) sorgt dafür, dass Cloud Run den Docker-Build statt
der Buildpacks verwendet.

Das Skript legt einen privaten Cloud-Storage-Bucket an, hängt ihn als Volume unter `/data` ein (kein
GCS-SDK nötig) und deployt den Server mit `--max-instances 1` (das Volume hat kein Locking), Scale-to-zero,
60-Minuten-Timeout und Session-Affinität für die WebSockets. Kosten: im Leerlauf praktisch null; eine
Instanz läuft nur, solange jemand synchronisiert oder ein Live-Raum offen ist. Die Blobs einer Band sind
wenige Megabyte.

Danach:

1. Die URL (`https://setlist-server-….run.app`) in der App unter **Band → Erweitert** eintragen – oder als
   Standard in `DEFAULT_SERVER` (`shared/…/Settings.kt`) setzen, dann ist sie in jeder App vorbelegt.
2. Für App-Links den Host als GitHub-Variable `SETLIST_APP_LINK_HOST` (z. B. `setlist-server-….run.app`)
   bzw. lokal als `setlistAppLinkHost=…` in `local.properties` setzen und die APK neu bauen. Android prüft
   `assetlinks.json` beim Installieren; dafür muss `ANDROID_SHA256` zum Signaturschlüssel der APK passen –
   das ist der Release-Keystore aus dem GitHub-Workflow (siehe README „Signieren“). Ohne Verifizierung fragt
   Android beim Öffnen eines Links, womit er geöffnet werden soll – das funktioniert ebenfalls.
3. iOS: `IOS_APP_ID` setzen und in Xcode die Associated Domain `applinks:<host>` eintragen; die App reicht
   geöffnete URLs an `MainViewModel.openLink` weiter (siehe `iosApp/README.md`).

Hinweis zur Genauigkeit: Über das Internet hängt die Synchronität vom Netz ab; im selben Raum bleibt der
WLAN-Sync genauer. Der Server-Raum ist für Abgleich, Einladungen, Links und Proben an getrennten Orten gedacht.
