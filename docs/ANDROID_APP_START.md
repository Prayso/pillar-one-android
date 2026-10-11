# Pillar One Android: Entwicklungsstart

Stand: 04.10.2026. Dieses Dokument fasst die besprochenen Entscheidungen und den Einstieg in die Android-Entwicklung zusammen. Die folgende Planung beschreibt den Zielumfang, keine bereits fertige Navigations-App.

Implementierungsstand: Im Projektordner `pillar-one-android` liegt ein Kotlin-/Compose-Projekt mit bestaetigter Application ID `de.pascal.pillarone`. Installation, Display-Verbindung, sichtbarer FF00-/FF01-Testhinweis und `OFF` sind am 04.10.2026 vom Nutzer am OnePlus und Display bestaetigt. Weitere Regressionen bleiben offen. A2 wird auf der Android-Seite vorbereitet, ohne die Firmware zu aendern. Der [BLE-v1-Vertrag](protocol/PILLAR_ONE_BLE_V1.md) und ein [separater Firmware-Auftrag](PILLAR_ONE_FIRMWARE_A2.md) beschreiben die gemeinsame Schnittstelle; A2 ist noch nicht hardwareseitig abgenommen. Werkzeugversionen, Startbefehle und Grenzen stehen in der [Projekt-README](../README.md).

Die uebergeordnete Planung steht in der [App- und Firmware-Roadmap](APP_ROADMAP.md).

## 1. Ziel und Entwicklungsreihenfolge

Pillar One bleibt ein eigenstaendiges Zusatzdisplay fuer Fahrzeugdaten. Das Handy liefert Navigation und spaeter Konfiguration sowie Fahrtenauswertung. Lokale Fahrzeuganzeigen sollen ohne Handy weiter funktionieren, sofern der OBD-Adapter Daten liefert.

1. Android-App auf dem vorhandenen OnePlus entwickeln und testen.
2. Zuerst eine BLE-Verbindung und einen simulierten Abbiegehinweis bis zum Display nachweisen.
3. Danach echte Navigation innerhalb der App integrieren und den Android-Navigations-MVP abnehmen.
4. Nach erfolgreichem Android-MVP eine native iOS-App entwickeln.
5. Menuekonfiguration und Fahrtenaufzeichnung als weitere Ausbaustufen umsetzen. Sie muessen die iOS-Portierung nicht verzoegern.

Qualitaetsziel: Beide Apps sollen auf ihren jeweiligen Plattformen zuverlaessig funktionieren, gut aussehen und sich passend bedienen lassen. Gleiche Markenidentitaet ist wichtig; pixelgleiche Oberflaechen sind nicht erforderlich.

## 2. Projektaufteilung und Namen

Es gibt insgesamt drei Softwareprojekte, aber fuer den Start kein viertes BLE-Codeprojekt:

| Projekt | Aufgabe | Technik |
| --- | --- | --- |
| Bestehende Pillar-One-Firmware | ESP32, Display, OBD, BLE-Empfaenger | PlatformIO, Arduino, NimBLE, LVGL |
| `pillar-one-android` | Android-App, Navigation, BLE, Oberflaeche | Kotlin, Jetpack Compose, Android BLE |
| `pillar-one-ios`, spaeter | iPhone-App mit entsprechendem Funktionsumfang | Swift, SwiftUI, CoreBluetooth |

Die App-Projekte bekommen eigene Projektordner und vorzugsweise eigene Git-Repositories neben dem Firmware-Projekt. Kein Android-Projekt innerhalb des Firmware-Quellcodeordners anlegen.

- Anzeigename auf beiden Handys: **Pillar One**.
- Android-Projektordner/Repository: `pillar-one-android`.
- Vorschlag fuer Paketname und Application ID: `de.pascal.pillarone`; vor Erstellung bewusst bestaetigen. Eine eigene Domain kann stattdessen die Namensbasis bilden.
- Paketname und Signaturschluessel spaeter moeglichst beibehalten, damit Updates die bestehende Installation ersetzen koennen.

Die gemeinsame BLE-Protokollspezifikation bleibt zunaechst im Firmware-Repository. Ein geplanter Ordner `docs/protocol/` kann spaeter Spezifikation und Testdaten aufnehmen; dieser Ordner ist durch dieses Dokument noch nicht angelegt. Ein separates Protokoll-Repository lohnt sich erst bei eigenstaendiger Versionierung oder mehreren unabhaengigen Nutzern.

## 3. Technologie und Entwicklungswerkzeuge

Festgelegte Richtung: **native Apps**, Android zuerst mit Kotlin und Jetpack Compose, iOS spaeter mit Swift und SwiftUI. Native Entwicklung bietet direkten Zugriff auf BLE-, Standort- und Hintergrundfunktionen. Zuverlaessigkeit entsteht trotzdem erst durch saubere Implementierung und Tests.

Flutter wurde als Alternative mit gemeinsamem Dart-/UI-Code besprochen. Es kann hochwertige Apps liefern, wird fuer diesen Einstieg aber nicht gewaehlt. Der Editor allein soll die Technologieentscheidung nicht bestimmen; Navigations-SDK, Hintergrundbetrieb und Plattformintegration sind wichtiger.

| Thema | Android | iOS, spaeter |
| --- | --- | --- |
| Empfohlenes Hauptwerkzeug | Android Studio | Xcode auf dem Mac |
| VS Code | Als Editor moeglich; SDK-/Buildwerkzeuge bleiben notwendig | Als Editor moeglich; Xcode-/iOS-Werkzeuge bleiben notwendig |
| Build | Android SDK, passendes JDK, Gradle Wrapper | Xcode, iOS SDK und Signierung |
| Native UI | Jetpack Compose | SwiftUI |
| BLE | Android-GATT-APIs | CoreBluetooth |

Android Studio ist nicht technisch zwingend, bietet aber fuer Kotlin/Compose die praktischere Kombination aus Vorschauen, Debugger, SDK-Verwaltung und Geraetemanagement. VS Code bleibt sinnvoll fuer Firmware, Dokumentation und Protokollspezifikation. Fuer iOS ersetzt VS Code weder Xcode noch Apples Signierungsanforderungen.

## 4. Testgeraet und Grenzen

Vorhandenes Android-Testgeraet:

| Eigenschaft | Wert |
| --- | --- |
| Modell | OnePlus 8 Pro |
| Arbeitsspeicher | 12 GB RAM |
| Betriebssystem | Android 13 / API 33 |
| Einsatz | BLE-, Navigations-, UI- und Hintergrundtests an echter Hardware |

Das Geraet ist fuer Navigation und BLE-Displayuebertragung gut geeignet. RAM und Rechenleistung sind kein erkennbarer Engpass. Android 13 begrenzt nicht automatisch den `targetSdk` der App.

Trotzdem beachten:

- Android verlangt Freigaben fuer Bluetooth und Standort.
- OnePlus-/OxygenOS-Energiesparen kann Hintergrundbetrieb beeinflussen.
- BLE, GPS und gesperrter Bildschirm muessen gemeinsam getestet werden.
- Tests auf Android 13 beweisen keine Kompatibilitaet mit allen neueren Versionen oder anderen Herstellern.
- Emulatoren helfen bei UI und Logik, ersetzen aber nicht die BLE-Tests am Display.
- Die iOS-App braucht spaeter ein echtes iPhone mit dokumentierter iOS-Version; Android-Ergebnisse sind kein iOS-Nachweis.

## 5. Android-Projekt anlegen

1. Aktuelle stabile Android-Studio-Version auf dem Mac installieren und den Einrichtungsassistenten samt Android SDK abschliessen.
2. Ein neues Projekt mit **Empty Activity** fuer Jetpack Compose erstellen, nicht mit **Empty Views Activity**.
3. Anzeigename **Pillar One**, Projektordner `pillar-one-android` und bestaetigten Paketnamen verwenden.
4. Kotlin und Kotlin DSL fuer die Buildkonfiguration nutzen.
5. Als vorlaeufigen Mindeststand **API 26 / Android 8.0** waehlen. Das ist ein pragmatischer Vorschlag, keine Pflicht; die spaetere SDK-Auswahl kann einen hoeheren Stand erfordern.
6. `compileSdk`, `targetSdk`, Gradle und JDK passend zur aktuellen stabilen Vorlage verwenden und die gewaehlten Versionen dokumentieren. Das von Android Studio empfohlene kompatible JDK nutzen.
7. Das unveraenderte Vorlagenprojekt auf dem OnePlus starten, bevor BLE oder Navigation eingebaut werden.
8. Gradle Wrapper und Projektkonfiguration versionieren; Keystores, Passwoerter, lokale SDK-Pfade und Zugangsdaten nicht ins Repository aufnehmen.

`minSdk` bestimmt, auf welchen alten Android-Versionen die App installiert werden kann. `compileSdk` stellt APIs fuer den Build bereit. `targetSdk` bestimmt, auf welchen Plattform-Verhaltensstand sich die App ausrichtet. Ein aktueller Zielstand ist mit Android 13 vereinbar, solange neuere APIs korrekt abgesichert werden.

## 6. App auf dem OnePlus installieren

### Entwicklung per USB

1. In den Handy-Einstellungen nach **Build-Nummer** suchen und siebenmal antippen, um die Entwickleroptionen zu aktivieren. Geraete-PIN gegebenenfalls direkt am Handy bestaetigen.
2. In den Entwickleroptionen **USB-Debugging** einschalten. Menuepfade koennen je nach OxygenOS-Version abweichen.
3. Ein USB-Kabel mit Datenuebertragung verwenden und das Handy mit dem Mac verbinden.
4. **USB-Debugging zulassen** am Handy bestaetigen. Dauerhaftes Vertrauen nur fuer den eigenen Mac erlauben.
5. In Android Studio das OnePlus als Zielgeraet waehlen und **Run** ausfuehren.
6. Die installierte App nach Abziehen des Kabels direkt am Handy starten.

Android Studio baut, signiert und installiert den Debug-Build automatisch. Ein Play-Store-Entwicklerkonto ist dafuer nicht erforderlich.

### Optional ueber das Terminal

Nach angelegtem Projekt, eingerichtetem SDK und verfuegbarem `adb`, jeweils im Android-Projektordner:

```sh
adb devices
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Der APK-Pfad gilt fuer ein Standardprojekt mit Modul `app` ohne weitere Varianten. `adb devices` muss das Handy als autorisiert anzeigen, nicht als `unauthorized`. Bei mehreren Geraeten das Ziel mit `adb -s <serial>` angeben. `-r` aktualisiert eine kompatibel signierte Installation normalerweise mit Datenerhalt; es loest keinen Signaturkonflikt.

### Signierung und spaetere Alltagstests

Auch Android-APKs muessen signiert sein. Lokal installierte Android-Apps haben aber keine mit Apples kostenlosem Personal Team vergleichbare woechentliche Provisioningfrist. Das automatisch erzeugte Android-Debug-Zertifikat ist standardmaessig 30 Jahre gueltig. Neue Builds werden signiert; die vorhandene Installation muss nicht jede Woche erneuert werden.

Fuer spaetere Alltagstests eine Release-APK mit eigenem langfristigem Schluessel bauen. Keystore sicher ausserhalb des Repositorys sichern und den `versionCode` bei Updates erhoehen. Debug und Release verwenden normalerweise unterschiedliche Schluessel; einen Wechsel mit Datensicherung oder getrennten Application IDs planen.

Bei manueller APK-Installation gegebenenfalls die Installationsquelle am Handy freigeben und aktuelle Installations-/Verifizierungsregeln pruefen. Google Play ist ein spaeterer Verteilungsweg, keine Voraussetzung fuer USB-Entwicklung.

Bei iOS gilt spaeter: Das kostenlose Xcode-Personal-Team hat derzeit eine **7-Tage-Frist** fuer Provisioning-Profile. Danach neu bauen und installieren. Diese Einschraenkung betrifft nicht regulaer ueber den App Store installierte Apps und wird durch einen anderen Editor nicht aufgehoben.

## 7. Architektur fuer den kleinen Android-Prototyp

Mit einem App-Modul starten, keine vorsorgliche Aufteilung in zahlreiche Libraries. Kleine Verantwortungsbereiche innerhalb der App vorsehen:

| Bereich | Verantwortung |
| --- | --- |
| UI / ViewModel | Compose-Ansichten, Nutzeraktionen und sichtbarer Zustand |
| BLE-Verbindung | Scan, GATT-Verbindung, Service-Erkennung, geordnete Schreibvorgaenge, Trennen |
| Protokoll | Nachrichtenmodelle, Kodierung, Dekodierung und Validierung ohne Android-UI-Abhaengigkeit |
| Navigation | Adapter zum ausgewaehlten SDK und normalisierte Navigationszustaende |
| Navigations-Service, spaeter | Laufende, vom Nutzer gestartete Navigation und BLE bei gesperrtem Handy |
| Lokale Daten, spaeter | Konfigurationsentwuerfe und Fahrten mit geeigneter Persistenz |

Kotlin Coroutines und StateFlow fuer asynchrone Zustaende verwenden. GATT-Operationen geordnet abarbeiten, Timeouts und Abbruch vorsehen und Ressourcen beim Sitzungsende freigeben. BLE und Navigation gehoeren nicht direkt in Compose-Composables.

Zunaechst nur Geraeteauswahl, Verbindungsstatus und einen Testhinweis anbieten. Noch keine Zielsuche, Fahrten-Datenbank, Cloud oder Anmeldung einbauen.

## 8. BLE: gemeinsame Spezifikation, eigener Plattformcode

Das Android-Handy arbeitet fuer den App-Dienst als BLE-Central/GATT-Client, Pillar One als Peripheral/GATT-Server. Android und iOS verwenden verschiedene BLE-APIs; ihre Verbindungsschichten werden getrennt implementiert. Die Firmware behaelt ihre bestehende OBD-BLE-Rolle, deren Parallelbetrieb zu pruefen ist.

Gemeinsam festlegen und spaeter versionieren:

- Service-/Characteristic-UUIDs und Eigenschaften.
- Geraete-ID, Faehigkeiten und Protokollversion.
- Nachrichtenformate, Einheiten, Manoever-Enum und UTF-8-Laengenlimits.
- Handshake, Annahmebestaetigungen und Fehlercodes.
- MTU-abhaengige Fragmentierung, Reihenfolge, Wiederholungen und Timeouts.
- Sitzungs-ID, Sequenznummer, Gueltigkeit und explizites Navigationsende.
- Gemeinsame Testdaten fuer Android, iOS und Firmware.

Eine HID-Verbindung oder Betriebssystem-Kopplung allein ist keine funktionierende App-Sitzung. Fuer das spaetere Protokoll erst nach Service-Erkennung und Handshake einen nutzbaren Zustand anzeigen. Geraetenamen und MAC-Adressen allein sind weder stabile Identitaet noch Berechtigungsnachweis. Verschluesselte/bonded Verbindung und bestaetigte Ersteinrichtung planen.

### Vorhandener Weg fuer den ersten Test

Der [Phone-Manager](../src/phone_manager.cpp) stellt bereits einen schreibbaren Service `FF00` und die Characteristic `FF01` bereit. Das [Navigationsmodell](../src/phone_manager.h) kennt aktive Navigation, Distanztext, Hinweistext und eine Manoevernummer.

| Element | Bestehende UUID |
| --- | --- |
| Navigationsservice | `0000ff00-0000-1000-8000-00805f9b34fb` |
| Schreibbare Characteristic | `0000ff01-0000-1000-8000-00805f9b34fb` |

Fuer einen begrenzten Test kann die App folgenden ASCII-Text als UTF-8 schreiben:

```text
1|200 m|Rechts|2
```

Das bedeutet: aktiv, Entfernung `200 m`, Hinweis `Rechts`, Manoever `2` fuer rechts. Die 16 Bytes passen auch in den ueblichen 20-Byte-Nutzdatenbereich bei ATT-MTU 23. Zunaechst Write with Response verwenden und GATT-Schreibvorgaenge nicht parallel starten.

Zum Beenden des Testhinweises akzeptiert der aktuelle Empfaenger:

```text
OFF
```

Display in den Kopplungsmodus versetzen bzw. die vorhandene Kopplungssituation beachten. Bei bereits gespeichertem iPhone nicht davon ausgehen, dass ein neues Handy automatisch zugelassen wird; gegebenenfalls bewusst ueber die Display-Einstellungen die alte Kopplung vergessen und neu koppeln.

**Grenzen:** Das alte Format ist kein fertiges Produktprotokoll. Es hat keinen App-Handshake, keine fachliche Annahmebestaetigung und keine definierte Nachrichtenfragmentierung oder Aktualitaetsgarantie. Ein erfolgreicher BLE-Write beweist noch nicht die sichtbare Anzeige. Den Hinweis am Display kontrollieren; bei Bedarf die Navigationsseite dort auswaehlen. Die neue produktive Schnittstelle bekommt projektspezifische 128-Bit-UUIDs und robusten Empfang. Vorhandenes HID/AMS-Verhalten nicht unbemerkt veraendern.

## 9. Navigation als erstes Produktziel

Das Handy nutzt GPS/Standort und Mobilfunk/Internet fuer Zielsuche, Karten und Routenfuehrung. Die App sendet die fuer das Display relevanten Informationen per BLE. Pillar One benoetigt dafuer kein eigenes GPS oder Mobilfunkmodem.

- Ziel suchen, Route anzeigen und Navigation bewusst starten/stoppen.
- Naechstes Manoever, numerische Entfernung und Strassenname senden.
- Links/rechts, geradeaus, Abzweigungen, Kreisverkehr mit Ausfahrtnummer, Wenden und Zielankunft behandeln.
- Routenabweichungen ueber das SDK neu berechnen.
- Restzeit/Reststrecke optional; keine vollstaendige Kartenprojektion auf Pillar One im MVP.
- Bei Wiederverbindung den aktuellen Zustand senden, keine alten Manoever nachliefern.
- GPS-Ausfall, Neuberechnung, BLE-Abbruch und Ende als eigene Zustaende behandeln; veraltete Displayhinweise muessen ablaufen.

### SDK-Auswahl vor echter Routenfuehrung

Ein bestehendes Navigations-SDK verwenden, kein eigenes produktives Map-Matching oder Rerouting bauen. Geeignete Angebote etwa von Google, Mapbox oder HERE anhand aktueller Bedingungen pruefen; noch keine Anbieterentscheidung treffen.

Auswahlkriterien: native Android-/iOS-Unterstuetzung, strukturierte Manoever-/Fortschrittsdaten, Hintergrundbetrieb, Routingqualitaet, Kosten, Offlinebedarf, Datenschutz, API-Key-Beschraenkungen und ausdruecklich erlaubte Anzeige auf dem externen BLE-Display. Eine Kartenansicht oder Routen-API allein ist keine aktive Navigationsengine.

Das Oeffnen von Google Maps per Intent liefert keinen allgemeinen Rueckkanal fuer Abbiegehinweise. Ein Android Notification Listener waere ein fragiler, sprach-/appabhaengiger experimenteller Adapter, nicht die Grundlage dieses MVP. Unter iOS ist ein allgemeines Auslesen anderer Navigations-Apps ebenfalls nicht vorgesehen. CarPlay/MFi ist kein automatischer Ersatz.

Offline-Navigation erst zusagen, wenn das ausgewaehlte SDK sie unterstuetzt und sie getestet wurde. Kein eigenes Benutzerkonto, eigener Server oder Cloudzwang fuer Pillar One; ein Anbieter kann trotzdem Internetzugang, API-Zugang und Gebuehren verlangen.

## 10. Berechtigungen und Hintergrundbetrieb

- Auf dem Android-13-Testgeraet `BLUETOOTH_SCAN` und `BLUETOOTH_CONNECT` als Laufzeitberechtigungen behandeln. Fuer unterstuetzte aeltere Versionen den passenden Legacy-/Scan-Standortpfad vorsehen.
- Die App muss fuer ihre Central-Rolle normalerweise nicht selbst werben; `BLUETOOTH_ADVERTISE` nicht pauschal anfordern.
- Fuer Navigation `ACCESS_COARSE_LOCATION` und `ACCESS_FINE_LOCATION` anfordern; Ablehnung und nur ungefaehren Standort sichtbar behandeln. Standort ist fuer den ersten fest codierten BLE-Test auf Android 13 nicht automatisch notwendig.
- `neverForLocation` fuer BLE-Scans nur verwenden, wenn tatsaechlich keine Standorte aus Scanergebnissen abgeleitet werden; moegliche Scanfilterung testen. GPS-Navigation braucht unabhaengig davon Standortfreigaben.
- Fuer Onlinekarten/Routing `INTERNET` deklarieren; das ist keine Laufzeitfreigabe.
- Navigation bewusst aus der sichtbaren App starten. Foreground Service mit Typ `location`, Navigationsbenachrichtigung und passenden Service-Berechtigungen vorsehen; fuer die BLE-Dauerverbindung auch `connectedDevice` samt Anforderungen pruefen.
- Ab Android 13 `POST_NOTIFICATIONS` und Ablehnung behandeln. Benachrichtigungsfreigabe und die Pflicht zum Foreground-Service-Hinweis nicht gleichsetzen.
- `ACCESS_BACKGROUND_LOCATION` nicht pauschal voraussetzen: Eine aus der sichtbaren App gestartete Standort-Foreground-Service-Sitzung kann bei gesperrtem Bildschirm weiterlaufen. Hintergrundstart und Wiederherstellung unterliegen weiteren Grenzen.
- Hersteller-Energiesparen mit Standardeinstellungen testen, danach gegebenenfalls bewusst angepasste Akkueinstellungen dokumentieren. Keine pauschale Zusage unbegrenzten Dauerbetriebs oder automatischer Fortsetzung nach erzwungenem Beenden.

Ein Foreground Service allein beweist noch keine zuverlaessige SDK-/BLE-Funktion. Der gesperrte Bildschirm ist ein eigenes Hardware-Abnahmekriterium.

## 11. Design und Bedienqualitaet

Gemeinsame Markenidentitaet fuer Android und iOS: abgestimmte Farben, Icons, Begriffe und Informationsstruktur. Android nutzt passende Material-Komponenten/-Symbole, iOS spaeter native Bedienelemente und SF Symbols. Dialoge, Navigation und Berechtigungsablaeufe muessen zur Plattform passen.

- Erste Ansicht ist eine nutzbare Navigations-/Geraeteansicht, keine Marketingseite.
- Klare Hierarchie fuer Ziel, naechstes Manoever und Display-Verbindungsstatus.
- Gute Kontraste, heller/dunkler Modus und ausreichend grosse Touchziele.
- Unterschiedliche Bildschirmgroessen, Systemleisten, Ausrichtung und vergroesserte Schrift testen.
- TalkBack auf Android; spaeter Dynamic Type und VoiceOver auf iOS.
- Keine ueberlappenden oder abgeschnittenen Texte; Fehler und Ladezustaende vollstaendig gestalten.
- Bluetooth aus, Freigabe fehlt, Suche, Verbindungsaufbau, Service-Erkennung, bereit, inkompatible Firmware und Verbindung verloren getrennt darstellen.
- Standortqualitaet und aktive Navigation nicht mit dem BLE-Verbindungsstatus vermischen.

Technische Prototyp-Ansicht und spaetere Alltagsansicht unterscheiden: Testwerkzeuge duerfen am Anfang sichtbar sein, gehoeren aber nicht dauerhaft in den normalen Navigationsablauf.

## 12. Kleine Umsetzungsschritte und Abnahme

| Schritt | Ergebnis | Gunstiger erster Nachweis |
| --- | --- | --- |
| A0: Projekt und Installation | Vorlagen-App laeuft auf OnePlus | Start nach Abziehen des USB-Kabels |
| A1: BLE-Prototyp | Display finden, verbinden, Testhinweis senden | `Rechts` und `200 m` am Display, danach `OFF` |
| A2: Robuster App-Vertrag | Versionierter Austausch und sichere Zustandswechsel | Kleine MTU, Fehler, Timeout und Wiederverbindung |
| A3: SDK-Prototyp | Echte strukturierte Navigationsdaten | SDK-Simulation/aufgezeichnete Standorte bis zum Display |
| A4: Android-MVP | Nutzbare Navigation einschliesslich Sperrbildschirm | Sichere Hardwaretests mit OBD/HID-Parallelbetrieb |
| A5: iOS-Portierung | Entsprechender nativer iPhone-Ablauf | Dieselben Protokollfixtures plus echte iPhone-Tests |

Android-Tests muessen mindestens abdecken:

- Protokollkodierung, Laengenlimits, Sequenzen und Zustandswechsel als Unit-Tests.
- UI mit simuliertem BLE-/Navigationszustand; keine Hardwarepflicht fuer reine Oberflaechentests.
- Berechtigungen erlaubt, verweigert, spaeter entzogen und nur ungefaehrer Standort.
- Bluetooth aus/an, Handy-/Display-Neustart, Kopplung und Wiederverbindung.
- Kleine MTU und fehlgeschlagene Schreibvorgaenge ohne blockierte Warteschlange.
- Links/rechts, Kreisverkehr, Wenden, Routenabweichung, Ankunft und Abbruch.
- GPS-Ausfall, Internetverlust und getrennte BLE-Verbindung.
- Gesperrter Bildschirm, App im Hintergrund, Energiesparen und erzwungenes Beenden.
- OBD-Anzeige und HID parallel; bestehendes iPhone-/AMS-Verhalten bei verfuegbarem iPhone als Regression.
- Heller/dunkler Modus, groessere Schrift, TalkBack und unterschiedliche Bildschirmgroessen.

Einrichtung und Bedienung im Stand. Fahrtests nur mit Beifahrer bzw. sicherem Testaufbau; keine App-Bedienung waehrend eigener Fahrt voraussetzen.

## 13. Spaetere Funktionen und Nicht-Ziele

**Menuekonfiguration:** Vorhandene Seiten aktivieren, sortieren und eine Startseite waehlen. Einstellungen per BLE uebertragen und bestaetigt in der Firmware speichern. Das ist Konfiguration, kein erneutes Firmware-Flashen. Ohne Handy bleiben Einstellungen und lokale Seiten erhalten. Lokalen Entwurf nie ungefragt beim Wiederverbinden ueberschreiben lassen.

**Fahrten:** OBD-Daten vom Display empfangen, lokal mit Zeitstempel/Gueltigkeit speichern, Diagramme und Export anbieten. Keine zweite konkurrierende OBD-Abfrage der App. GPS optional mit Freigabe; Aufzeichnung ohne Handy ist ein gesonderter Firmware-Ausbau.

Nicht Teil des ersten Navigations-MVP: Firmware-OTA, frei platzierbare Dashboards, Kartenprojektion auf dem Display, Android-Medienmetadaten, Cloudkonto, autonome Fahrtenaufzeichnung oder garantierte Offline-Navigation.

## 14. Naechste Entscheidungen und Startauftrag

Vor Projekterstellung bestaetigen: Projektpfad, Paketname und installierte Android-Studio-/SDK-Version. Das OnePlus samt Android 13 ist bereits als Testgeraet festgelegt.

Vor echter Navigation klaeren: Anbieterbudget, Offlinebedarf, SDK-Mindestversionen und Rechte fuer externe Displayhinweise. iPhone-Modell und iOS-Version vor der Portierung festlegen.

Startauftrag fuer die Android-Entwicklung:

> Erstelle eine native Pillar-One-Android-App mit Kotlin und Jetpack Compose in einem eigenen Projekt. Testgeraet ist ein OnePlus 8 Pro mit Android 13 und 12 GB RAM. Implementiere zuerst Berechtigungen, BLE-Geraetesuche, Service-Erkennung, geordnetes Schreiben, Verbindungsstatus und einen kurzen Testhinweis ueber den bestehenden FF00-/FF01-Dienst. Noch kein Navigations-SDK und keine Fahrtenaufzeichnung einbauen. Trenne UI, BLE und Protokoll, beachte kleine MTU und kennzeichne den bestehenden Dienst als begrenzten Prototyp. Die produktive Schnittstelle muss spaeter auch fuer eine native iOS-App geeignet sein. Pruefe den Testhinweis an echter Displayhardware, bevor weitere Funktionen folgen.

## 15. Grundlagen

- [Android Studio](https://developer.android.com/studio)
- [Android: App erstellen und auf Geraeten ausfuehren](https://developer.android.com/studio/run)
- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [Android: App-Signierung](https://developer.android.com/studio/publish/app-signing)
- [Android: Bluetooth-Berechtigungen](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android: Standortberechtigungen](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Android: Foreground-Service-Typen](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Apple: Personal Team und Provisioning](https://developer.apple.com/support/compare-memberships/)

Werkzeugversionen, Berechtigungsdetails, Anbieterbedingungen und Installationsregeln beim Entwicklungsstart gegen den tatsaechlichen Zielstand pruefen.