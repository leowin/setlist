# iOS-App

Benötigt einen Mac mit Xcode und [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```
cd iosApp
xcodegen generate
open Setlist.xcodeproj
```

Das Xcode-Projekt baut vor jedem Build das Kotlin-Framework `Shared` aus `../shared`
(Task `embedAndSignAppleFrameworkForXcode`). Die ganze Oberfläche und Logik liegt im Kotlin-Modul;
Swift enthält nur den Einstieg (`SetlistApp.swift`).

## Audio und Noten

- Die Audio-Engine ist C++ (`audio/`) mit CoreAudio-Backend (`audio/src/ios`), angebunden über die
  C-Schnittstelle `audio/src/capi/setlist_audio.h` und Kotlin/Native-cinterop. Vor dem ersten Build
  `./audio/tools/build_ios.sh` ausführen (das Xcode-Projekt macht das automatisch, wenn die Bibliothek fehlt).
- Der Ordner `shared/web/alphatab` wird als Bundle-Ressource eingebunden (Notenbild).
- `Info.plist`: Bonjour-Berechtigungen für die Band-Suche, Hintergrund-Audio für das Metronom.
- **Band-Einladungen und Teilen-Links** (`https://<server>/join#…`, `…/s/<id>#<key>`): In Xcode unter
  Signing & Capabilities die Associated Domain `applinks:<server-host>` eintragen; der Server liefert
  `/.well-known/apple-app-site-association`, wenn `IOS_APP_ID` (`<TeamID>.at.zentriert.setlist`) gesetzt ist.
  `SetlistApp.swift` reicht geöffnete URLs über `onOpenURL` an `openLink` im Kotlin-Modul weiter.
- Krypto für den Band-Abgleich nutzt CommonCrypto (`shared/src/iosMain/.../sync/Crypto.ios.kt`).

Dieser Teil ist bisher nur auf Android getestet; die ersten iOS-Builds auf einem Mac werden Nacharbeiten brauchen.
