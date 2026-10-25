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
- **Fußpedal/Tasten:** Pfeiltasten, Bild↑/↓, Leertaste/Enter und die Lautstärketasten blättern
  (Bluetooth-Page-Turner funktionieren damit in der Regel direkt).
- **Bildschirm:** 💡 hält den Bildschirm wach. ⏻ schaltet ihn „aus“ (schwarz, minimale
  Helligkeit) – ein Tippen oder ein Pedaldruck schaltet sofort wieder ein, ohne Sperrbildschirm.

## Bauen

Benötigt JDK 17+ und das Android SDK (`local.properties` mit `sdk.dir=…` oder `ANDROID_HOME`).

```
./gradlew testDebugUnitTest assembleRelease
```

Die APK liegt danach unter `app/build/outputs/apk/release/app-release.apk` (mit dem Debug-Schlüssel
signiert, also direkt installierbar). GitHub Actions baut sie bei jedem Push ebenfalls
(Artefakt `setlist-apk`).
