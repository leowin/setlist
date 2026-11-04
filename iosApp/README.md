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
