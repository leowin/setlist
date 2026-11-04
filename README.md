# Setlist

Setlisten-App für Musiker (Android). Setlists aus Google Notizen (Keep) importieren, in Lieder
zerschneiden und auf der Bühne mit großer Schrift durchblättern.

## Funktionen

- **Mehrere Setlists/Konzerte** speichern, umbenennen, löschen, als Text teilen.
- **Import per Kopieren/Einfügen:** Text aus Google Notizen oder einer beliebigen anderen App
  kopieren → „Neue Setlist“ → „Aus Zwischenablage einfügen“. Aus Google Notizen geht es
  auch direkt über ⋮ → „Senden“ → *Setlist*.
- **Zerschneiden:** Die App schlägt Schnitte vor (nummerierte Zeilen 1., 2., … → Leerzeilen-Blöcke →
  jede Zeile). Tippen auf eine Zeile setzt/entfernt einen Schnitt. Erste Zeile eines Lieds = Titel,
  der Rest = Anweisungen. Zeilen vor dem ersten Schnitt (z. B. Überschrift) werden ignoriert.
- **Lieder-Bibliothek:** Jedes importierte oder bearbeitete Lied wird mit seinen Anweisungen
  gespeichert (gleicher Titel → Anweisungen werden aktualisiert). Lieder ohne Anweisungen bekommen
  beim Import die Anweisungen aus der Bibliothek. Lieder lassen sich aus der Bibliothek in jede
  Setlist übernehmen (als Kopie – Änderungen für ein Konzert betreffen andere Setlists nicht).
- **Konzertansicht:** aktuelles Lied groß, darunter (Querformat: daneben) das nächste bzw. die
  nächsten zwei Lieder mit Anweisungen. Große *Zurück*/*Weiter*-Buttons, Schriftgröße einstellbar,
  Antippen der Position springt zu einem beliebigen Lied.
- **Tempo & Taktart** pro Lied (BPM tippen, ± oder Tap-Tempo; 2/4 … 12/8). Gespeichert in einer
  Partitur (`Score`), die alle Kopien eines Lieds in Setlists und Bibliothek teilen.
- **Metronom** in der Konzertansicht: Start/Stop, Tempo mit ± anpassen (nur für dieses Konzert, oder
  „Übernehmen“ speichert es), Vorzähler, Unterteilung (Achtel/Triolen/16tel), Lautstärke. Der Hintergrund
  des aktuellen Lieds zeigt die Schläge als große Spalten – die laufende leuchtet, die 1 heller –, damit
  man den Takt auch von weit weg sieht. Audio läuft nativ (C++ + Oboe) und ist sample-genau.
- **Noten aus MIDI-Dateien:** Im Noten-Bildschirm eines Lieds (♪ in Setlist oder Bibliothek) eine `.mid`
  wählen – oder die Datei aus Dateimanager/Mail mit „Öffnen mit“ an Setlist geben. Pro Spur wählst du
  Bass, Schlagzeug, Gitarre, Gesang oder „Weglassen“ (Vorschlag automatisch nach Kanal/Programm/Name);
  Tempo und Taktart der Datei können übernommen werden.
- **Abspielen & Mixer:** Noten werden mit einem SoundFont-Synth (TinySoundFont, Auszug aus GeneralUser GS)
  sample-genau zum Metronom abgespielt. Pro Instrument Lautstärke, Stumm und Solo; „Ich spiele …“ plus
  „Nur ich“ / „Alle außer mir“ als Schnellwahl für jedes Handy.
- **Noten-Editor** (Stift-Symbol im Noten-Bildschirm): Pianoroll für Bass/Gitarre/Gesang, Raster mit einer
  Zeile pro Pad fürs Schlagzeug. Tippen zeichnet/wählt/löscht (Werkzeug wählbar), lange drücken und ziehen
  verschiebt oder verlängert (rechter Rand), Notenwerte 1/2 bis 1/32 auch als Triole, Zoom, Undo/Redo,
  Quantisieren (Auswahl oder alles). Unten eine kompakte Klaviatur (2 Oktaven, Oktav-Schalter) bzw. 16
  Drumpads, frei belegbar („Pads belegen“). **Einspielen** mit Vorzähler und Metronom, optional als Loop
  über Takte, Ersetzen oder Overdub, Quantisierung mit Raster und Stärke; **Schritt-Eingabe** setzt Noten
  am Cursor. Die Latenz zwischen Tippen und Klang wird mit „Im Takt tippen“ kalibriert und herausgerechnet.
- **Notenbild & Tabs** (♫ im Noten-Bildschirm): klassische Notation mit alphaTab – Bass und Gitarre
  wahlweise als Noten, Tab oder beides (Bundzuordnung automatisch), Gesang als Notenzeile, Schlagzeug als
  Schlagzeugnotation. Beim Abspielen folgt ein Cursor. In der Konzertansicht schaltet ♫ zwischen den
  Text-Anweisungen und den Noten des eigenen Instruments um. Rhythmen werden auf 1/32 quantisiert,
  längere Werte als gebundene Noten geschrieben; Triolen erscheinen vorerst als nächstliegende gerade Werte.
- **Band-Sync im WLAN** („Band“ auf der Startseite): Ein Handy hostet, die anderen finden es per mDNS
  (oder IP-Eingabe) und treten bei. Der Host verteilt seine Setlist samt Noten; Weiter/Zurück,
  Metronom-Start und Abspielen folgen dem Host auf allen Handys **gleichzeitig**: Die Uhren werden per
  UDP-Rundlauf (NTP-Prinzip, beste Messungen, Median, Drift-Regression) abgeglichen, der Start wird als
  Host-Zeitpunkt gesendet und jedes Handy plant ihn sample-genau über die Zeitstempel seines
  Audio-Streams. Eine einstellbare Startverzögerung gleicht z. B. Bluetooth-Kopfhörer aus, und der
  Sync-Test lässt alle Handys jede Sekunde gemeinsam klicken und blinken.
- **Bands ohne Datenhoheit des Servers** („Band“ auf der Startseite): Eine App kann in mehreren Bands
  spielen; jedes Lied und jede Setlist gehört genau einer Band (oder ist „Privat“), oben links wird die
  Band gewählt. Lieder und Setlists lassen sich in eine andere Band **kopieren** (⋮ → „In andere Band
  kopieren“), die Kopie ist danach unabhängig. Eine Band hat einen zufälligen Schlüssel, der nur auf den
  Handys liegt; Einladung per **QR-Code** oder Link (Kamera-App scannen oder Link in der App einfügen),
  keine Konten. Alles, was zum Server geht, ist damit verschlüsselt – er kennt weder Bandnamen noch
  Liedtitel und löscht Räume nach 90 Tagen ohne Zugriff. Änderungen werden automatisch abgeglichen
  (auch live, wenn die Band im Internet-Raum verbunden ist); bearbeiten zwei Mitglieder dasselbe Lied
  gleichzeitig, bleiben beide Fassungen erhalten („… (Fassung von Name)“). Beim Verlassen einer Band bleiben
  ihre Lieder als private Kopien auf dem Handy.
- **Teilen-Links:** Lied oder Setlist (mit Noten) als Link teilen, gültig 7/30/90 Tage. Der Schlüssel steht nur
  im Link (hinter `#`), der Server speichert Verschlüsseltes. Im Browser zeigt eine Vorschau Titel, Tempo und
  Anweisungen und bietet „In der App öffnen“; die App übernimmt den Inhalt in eine Band oder privat.
- **Band über Internet:** Der Server (`server/`, Docker/Cloud Run) verbindet die Band an getrennten Orten
  über ihren Raum; der Server ist die gemeinsame Uhr. Einrichtung, Kosten und App-Links: `server/README.md`.
- **Fußpedal/Tasten:** Pfeiltasten, Bild↑/↓ und Leertaste/Enter blättern (Bluetooth-Page-Turner
  funktionieren damit in der Regel direkt). Die Lautstärketasten regeln die Lautstärke.
- **Bildschirm:** 💡 hält den Bildschirm wach. ⏻ schaltet ihn „aus“ (schwarz, minimale
  Helligkeit) – ein Tippen oder ein Pedaldruck schaltet sofort wieder ein, ohne Sperrbildschirm.

## Aufbau

Kotlin Multiplatform + Compose Multiplatform, damit dieselbe App auch für iOS gebaut werden kann:

- `shared/` – Modell, Logik und die ganze Oberfläche (`commonMain`); Plattform-Details wie
  Bildschirm wach halten, Teilen und Einstellungen in `androidMain` / `iosMain`.
- `androidApp/` – Android-Einstieg (Activity, Manifest, Icon, Tasten/Pedal, „Senden“-Import).
- `iosApp/` – iOS-Einstieg (Swift + XcodeGen-Spec), siehe `iosApp/README.md`.
- `shared/web/alphatab/` – alphaTab (MPL-2.0) samt Bravura-Schrift (OFL) für das Notenbild; Android liefert
  den Ordner als Assets aus, iOS bindet ihn als Bundle-Ressource ein.
- `server/` – Band-Server (Ktor): verschlüsselte Blobs + Manifest je Band, Teilen-Links mit Browser-Vorschau,
  Live-Raum per WebSocket. `shared/.../sync/` enthält Krypto (`Crypto.kt`), Manifest-Merge und `SyncEngine`.
- `audio/` – C++-Audio-Kern (Metronom, Klick-Synth, Sequencer, SoundFont-Synth) mit Oboe-Backend für
  Android und CoreAudio-Backend für iOS (C-API + cinterop, siehe `iosApp/README.md`). Drittanbieter und SoundFont-Erzeugung: `audio/third_party/README.md`.
  Eigene Tests: `cmake -S audio -B build/audio && cmake --build build/audio && ctest --test-dir build/audio`.

## Bauen

Benötigt JDK 17+ und das Android SDK (`local.properties` mit `sdk.dir=…` oder `ANDROID_HOME`).

```
./gradlew :shared:testDebugUnitTest :androidApp:assembleRelease
```

Die APK liegt danach unter `androidApp/build/outputs/apk/release/androidApp-release.apk` (mit dem
Debug-Schlüssel signiert, also direkt installierbar). GitHub Actions baut sie bei jedem Push ebenfalls
(Artefakt `setlist-apk`); das iOS-Framework lässt sich über „Run workflow“ auf einem macOS-Runner
kompilieren (manueller Start, da macOS-Runner für dieses Repo nicht automatisch verfügbar sind).
