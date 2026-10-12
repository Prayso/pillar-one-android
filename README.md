# Pillar One Android

Native Android-App mit Kotlin und Jetpack Compose. **A1-BLE-Prototyp mit
Vorbereitung des A2-App-Vertrags**, noch kein Navigations-SDK.
Paketname und Application ID: `de.pascal.pillarone`.

Die untere Navigation wechselt zwischen **Display** (Verbindung und Testhinweis),
**Konfiguration** und **Auswertungen**. In der Konfiguration lassen sich vorhandene Pillar-One-Seiten auslesen,
sortieren, ein-/ausblenden und als Startseite festlegen. Beim Wechsel bleiben die
BLE-Sitzung und die Scrollposition der Displayseite erhalten.

## Beschleunigungsauswertungen

**Auswertungen** unterscheidet Beschleunigung und Fahrten. Fahrten sind vorerst
nur ein Hinweis auf den spaeteren Ausbau. **Automatische GPS-Auswertung aktivieren**
zeigt einmalig die Einwilligung fuer Standort/Geschwindigkeit, lokale Speicherung
und Hintergrundbetrieb; danach folgt die genaue Android-Standortfreigabe.
Die Einwilligung bleibt gespeichert und kann jederzeit auf der Auswertungsseite
oder in der Aufzeichnungsbenachrichtigung deaktiviert werden.
Ein verbundenes Display mit automatischem Beschleunigungs-Seitenstatus ist
erforderlich. Die Standortfreigabe wird nicht fuer die normale BLE-Verbindung
angefordert. Nur auf dafuer freigegebenen, abgesperrten Strecken messen und
die App vor der Fahrt bedienen.

Abgeschlossene 0-100- und 100-200-km/h-Messungen werden automatisch in der
privaten SQLite-Datenbank auf dem Handy gespeichert: Intervall, Startdatum/-zeit
mit Zeitzone, Start- und Zielkoordinaten mit Standortgenauigkeit (sofern verfuegbar),
GPS-/Auto-Dauer und Display-ID. Jeder Quellenwert behaelt intern seinen eigenen
Start-/Endzeitpunkt. Die Fahrzeugmessung am Display ist massgeblich; GPS und Auto
messen unabhaengig, aber GPS wird nur einer tatsaechlichen Auto-Messung zugeordnet.
Nur eindeutige Ergebnisse
mit Start und Ende innerhalb von jeweils zwei Sekunden werden zusammengefuehrt.
Fehlende Quellen/Standorte werden als nicht verfuegbar angezeigt, nicht geschaetzt.
Die Historie bleibt bei App-Neustarts erhalten, nicht nach Deinstallation oder
Loeschen der App-Daten. Es gibt keinen Cloud-Upload und kein Android-Backup.

GPS nutzt ausschliesslich frische GPS-Fixes (maximal zwei Sekunden alt,
Standortgenauigkeit bis 30 m, bestaetigte Geschwindigkeitsgenauigkeit bis 3 m/s);
Fixes ohne Geschwindigkeitsgenauigkeit und simulierte Standorte werden
ausgeschlossen. Der 0-100-Start ist
der letzte Stillstandsfix vor der ersten Bewegung ueber 1 km/h;
100-/200-km/h-Uebergaenge
werden zwischen GPS-Samples interpoliert. Luecken ueber zwei Sekunden und
deutliche Verzoegerung brechen unvollstaendige GPS-Messungen ab.
Auto-Zeiten stammen direkt aus der realen OBD-Messung der Firmware, nicht aus
einer zweiten OBD-Abfrage. Bereits vor Aufzeichnungsstart abgeschlossene
Display-Ergebnisse und Firmware-Demowerte werden nicht importiert.

Die App bereitet bei sichtbarer UI einen Standort-Foreground-Service vor.
Eine dauerhafte Benachrichtigung zeigt Bereitschaft oder aktive GPS-Erfassung an.
Die Android-Benachrichtigungsfreigabe und der Kanal Beschleunigungsauswertung
muessen aktiviert sein; bei Ablehnung bleibt die GPS-Einwilligung gespeichert,
aber die App zeigt den notwendigen Einstellungsschritt statt still im
Hintergrund aufzuzeichnen.
GPS wird erst beim Oeffnen der Beschleunigungsseite am Pillar One abonniert und
beim Verlassen wieder beendet. Home, Sperren und Activity-Ende beenden einen
bereits gestarteten Service nicht; eine Hintergrundstandortberechtigung wird
nicht benoetigt. Der Bildschirm muss nicht eingeschaltet bleiben.
Trennen, Deaktivieren, Force-Stop oder Androids Task-Manager-Stop beenden die
Bereitschaft. Nach Prozessende muss die App vor der naechsten Messung wieder
geoeffnet werden: Android erlaubt keinen beliebigen Start eines
Standort-Foreground-Service aus dem Hintergrund.
Vollstaendige Ergebnisse bleiben gespeichert; angefangene Messungen werden
beim Verlassen der Displayseite verworfen. Bei Speicherfehlern
zeigt die App einen Fehler und bietet eine Wiederholung an; ungespeicherte
Ergebnisse bleiben nur bis zum Prozessende im Arbeitsspeicher.

Im Beschleunigungs-Tab stehen kompakte Karten mit Intervall, Datum/Uhrzeit
und Zeiten in der Reihenfolge **GPS / Auto**; ein Strich bedeutet nicht gemessen.
Der Filter zeigt alle, 0-100 oder 100-200 km/h. Sortiert werden kann nach
Datum/Uhrzeit (neueste/aelteste) sowie separat nach GPS- oder Auto-Zeit
(schnellste/langsamste); fehlende Zeiten stehen jeweils am Ende.
Antippen oeffnet eine eigene scrollbare Detailseite mit Zeitzone sowie den Zeiten,
Start-/Zielzeitpunkten, Koordinaten, Genauigkeiten und Display-ID je Messquelle.
Der Pfeil oben links oder Android-Zurueck fuehrt zur Liste zurueck; Filter,
Sortierung und Listenposition bleiben erhalten. Das untere Menue bleibt sichtbar.
Nach links wischen zeigt einen roten Papierkorb; erst dessen Antippen loescht
die gesamte Messung samt GPS-/Auto-Ergebnissen und Standorten dauerhaft.
Die GPS-Freigabe wird unter **Einstellungen** verwaltet.

Auto-Messungen erfordern die separat zu flashende Beschleunigungs-Erweiterung:
[BLE-Vertrag](docs/protocol/PILLAR_ONE_ACCELERATION_V1.md).
Grafiken, Vergleiche, Export und eine Nachsynchronisation von Messungen ohne
aktive Handy-Aufzeichnung sind noch nicht implementiert.
Die Datenbank wird verlustfrei auf das Endpunkt-Schema aktualisiert;
alte Messungen behalten ihre Werte, haben aber keinen nachtraeglich erfundenen
Zielstandort. Gespeicherte Standortpunkte sind echte GPS-Fixes in zeitlicher
Naehe zum Start/Ziel, keine Garantie fuer eine zentimetergenaue Schwellenposition.
GPS-Qualitaet, Hintergrundbetrieb, zeitliche Quellenzuordnung und reale OBD-Messungen muessen
mit Handy und Display auf einer geeigneten Teststrecke abgenommen werden.

Die Menuefunktion setzt die neue Firmware mit Menu-Capability voraus; aeltere
Displays zeigen einen Updatehinweis, Navigation bleibt unveraendert nutzbar.
Die App liest Namen und stabile Seiten-IDs vom Display, nicht den UI-Code.
Entwuerfe bleiben beim Seitenwechsel, Drehen und Wiederverbinden erhalten
(waehrend dieses App-Prozesses, getrennt je Display). Erneutes Auslesen
verwirft lokale Aenderungen nur nach Bestaetigung.

**Auf Pillar One speichern** uebertraegt das gesamte Menue mit erwarteter
Revision. Erst nach dauerhafter Firmware-Bestaetigung und vollstaendigem
Ruecklesen erscheint der Speichererfolg. Bei Abbruch nicht blind erneut
senden: verbinden und den tatsaechlichen Stand auslesen. Die Startseite bleibt
sichtbar; zum Ausblenden zuerst eine andere Startseite waehlen.
Navigation und Medien sind keine waehlbaren Startseiten, da diese Seiten
ein verbundenes Handy benoetigen. Die App liest diese Einschraenkung aus
dem Seitenkatalog der Firmware.
Seiteninhalte, eigene Grafiken und neue Seiten sind noch nicht bearbeitbar.
Die Firmware muss separat gebaut/geflasht werden; die App bietet kein OTA.
Vertrag und Hardware-Abnahme:
[Menue-Erweiterung](docs/protocol/PILLAR_ONE_MENU_V1.md).

## Werkzeugstand

| Werkzeug | Version |
| --- | --- |
| Android Studio auf dem Entwicklungsrechner | 2026.2 |
| Android Gradle Plugin | 9.4.1, integrierte Kotlin-Unterstuetzung |
| Gradle Wrapper | 9.6.0, Distribution mit SHA-256-Pruefung |
| Kotlin / Compose Compiler Plugin | 2.4.20 |
| Compose BOM | 2026.09.00 |
| Java-Bytecode | 17 |
| Build-JDK auf dem Entwicklungsrechner | Android-Studio-JBR 25.0.3 |
| Android SDK | compileSdk / targetSdk 37, minSdk 26 |
| SDK Build Tools | 36.0.0 |

Android Studio oeffnet diesen Ordner als Gradle-Projekt. Das SDK ueber den
SDK Manager konfigurieren und als Gradle-JDK das mitgelieferte JBR verwenden.
`local.properties` mit lokalem SDK-Pfad bleibt unversioniert. Alternativ im
Terminal `ANDROID_HOME` auf das installierte SDK und `JAVA_HOME` auf ein
kompatibles JDK setzen:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die Compose-Oberflaechentests nutzen simulierte BLE-Zustaende ohne Display:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Dafuer muss ein Android-Geraet oder Emulator verbunden sein. Echte BLE-Tests
benoetigen das OnePlus und das Pillar-One-Display. Keystores, Zugangsdaten und
lokale SDK-Pfade nicht versionieren.

## Erster Displaytest

1. Display in den Kopplungsmodus versetzen. Eine alte Handy-Kopplung bei Bedarf
   bewusst in den Display-Einstellungen entfernen.
2. App auf dem OnePlus 8 Pro (Android 13) starten und Bluetooth-Freigabe erteilen.
   Unter Android 8 bis 11 sind fuer die BLE-Suche zusaetzlich genaue
   Standortfreigabe und eingeschalteter Standortdienst erforderlich.
3. Bluetooth einschalten und **Display suchen** waehlen. Die Suche endet nach
   zehn Sekunden und filtert auf den angebotenen FF00-Service.
4. Das gefundene Display verbinden. **Display verbunden** bedeutet beim alten Testdienst nur:
   Service und schreibbare Characteristic wurden erkannt, kein App-Handshake.
5. **Testhinweis senden** schreibt `1|200 m|Rechts|2` (16 UTF-8-Bytes).
   `Rechts` und `200 m` direkt am Display kontrollieren; gegebenenfalls dort die
   Navigationsseite auswaehlen.
6. **Hinweis beenden** schreibt beim alten Testdienst `OFF`. Das Ende ebenfalls am Display
   kontrollieren, dann trennen.

Android 12+ nutzt `BLUETOOTH_SCAN` und `BLUETOOTH_CONNECT`, ohne
Standortfreigabe fuer diesen Test. `neverForLocation` ist deklariert, da keine
Standorte aus Scandaten abgeleitet werden. Die Scanerkennung mit echter
Firmware muss trotzdem geprueft werden. Beim Verbinden wird ab Android 13 die
Benachrichtigungsfreigabe angefragt; Ablehnung blockiert BLE nicht. Keine
Advertising-, Internet- oder Hintergrundstandortfreigabe wird angefordert.
Die optionale GPS-Beschleunigungsaufzeichnung fragt separat den genauen
Vordergrundstandort an.

## Architektur und Grenzen

- `ui/`: Compose-Ansicht und ViewModel, vom BLE-Transport getrennt.
- `ble/`: Scan, GATT-Verbindung, Service-Erkennung, v1-Subscription/Handshake
   und geordnete Schreibvorgaenge mit Transport- und App-Antwort-Timeouts;
   gemeinsame Application-Runtime und connectedDevice-Foreground-Service.
- `media/`: separat freizugebender NotificationListenerService fuer echte
   MediaSessions und Mediensteuerung; Metadaten-Collector im BLE-Service.
- `protocol/`: UI-unabhaengiger Legacy-/v1-Codec, Byte-/Feldvalidierung,
  Fragmentassemblierung und Zuordnung von App-Antworten.

Ein einzelner Write ist gleichzeitig erlaubt. Weitere Schreibaktionen sind
bis zur Antwort gesperrt; es gibt keine Warteschlange veralteter Manoever.
Legacy bleibt bei maximal 20 Nutzdatenbytes (ATT-MTU 23), ohne Fragmentierung.
V1 fragmentiert Nachrichten bis 192 Bytes in einzelne GATT-Frames mit maximal
20 Bytes inklusive Header. Keine groessere MTU ist erforderlich.

Eine aus der sichtbaren App gestartete BLE-Verbindung bleibt bei Home,
Activity-Ende, Entfernen aus der App-Uebersicht und gesperrtem Handy im
connectedDevice-Foreground-Service aktiv. UI und Service teilen einen Controller;
das ViewModel besitzt weder GATT noch den Medien-Collector. Scans laufen nur
bei sichtbarer UI. Die leise Verbindungsbenachrichtigung bietet **Trennen**.
Bei verweigerter Notification-Freigabe zeigt Android den Service weiterhin im
Task Manager an, nicht im Benachrichtigungsbereich. **Vor dem Trennen OFF
senden.** Im Legacy-Modus garantiert das alte Protokoll weder Annahme noch
Ablauf eines Hinweises, und Trennen kann einen alten Hinweis auf dem Display
zuruecklassen. V1-Firmware muss Navigation bei Disconnect sofort loeschen.
Ein erfolgreicher GATT-Write ist keine Bestaetigung der sichtbaren Anzeige.
Begrenzte Wiederverbindung funktioniert waehrend der Service-Sitzung auch
im Hintergrund; echte Hintergrundnavigation fehlt weiterhin.
v1-Geraeteidentitaet wird erst nach dem Handshake aus der Firmware gelesen.

Mit freigegebenem Medienzugriff beobachtet die App echte MediaSessions und
uebertraegt Titel, Interpret und Wiedergabezustand auch im Hintergrund. Der
5-Sekunden-Heartbeat bleibt aktiv. Mediensteuerung geht an den ausgewaehlten
Player, ohne doppelte HID-Aktion. Bei explizitem Trennen wird die Bridge-CCCD
nach dem laufenden Austausch abgemeldet, bevor der Service beendet wird.
Medienvertrag und offene Hardware-Abnahme:
[Medien-Erweiterung](docs/protocol/PILLAR_ONE_MEDIA_V1.md).

Kein automatischer Neustart nach Prozessende (START_NOT_STICKY), kein Boot-
Receiver, kein unbegrenzter Wake-Lock und keine automatische Ausnahme von
Akkuoptimierung. Force-Stop und Androids Task-Manager-Stop beenden den Betrieb.
OnePlus/OxygenOS kann Hintergrundprozesse zusaetzlich beenden; bei Problemen
die App-Akkueinstellungen fuer Hintergrundbetrieb/uneingeschraenkte Nutzung
bewusst pruefen. Dauerbetrieb und gesperrter Bildschirm sind noch nicht an
echter Hardware abgenommen.

Am 04.10.2026 vom Nutzer auf dem OnePlus und echten Display bestaetigt:
App installiert, Display verbunden, `Rechts / 200 m` sichtbar und `OFF`
funktioniert. Breitere Regressionen wie Berechtigungsentzug, Bluetooth-Wechsel
und OBD-/HID-Parallelbetrieb sind damit noch nicht abgenommen.

## A2: eigener App-Vertrag, Firmware separat

Der neue Vertrag nutzt einen eigenen 128-Bit-Service, einen
HELLO/HELLO_ACK-Handschlag, geordnete Fragmente mit maximal 20 Bytes,
Sitzung/Sequenz, fachliche ACKs und einen TTL-begrenzten Navigationszustand.
Die App-Sitzung ist erst nach erfolgreichem Handshake nutzbar.
Vor der v1-Subscription startet die App bei Bedarf die Android-Systemkopplung
und wartet bis zu 60 Sekunden auf ihren Abschluss. Den Kopplungsdialog am
OnePlus bestaetigen; fuer die Ersteinrichtung muss das Display im lokal
gestarteten Kopplungsmodus sein. Bestehende Android-Bonds werden wiederverwendet,
aber erst HELLO_ACK bestaetigt die App-Sitzung. Ablehnung und Timeout sind
eigene Fehler; der normale 5-Sekunden-Schreibtimeout bleibt unveraendert.
Der Systemdialog darf die App verdecken; Home und Sperren lassen eine bereits
gestartete Service-Verbindung bestehen. Trennen oder Ablauf der Kopplungsfrist
beenden sie. Eine bereits gestartete
Systemkopplung wird nicht durch versteckte Android-APIs abgebrochen.

Die Oberflaeche zeigt Verbindungsaufbau, Kopplung und Bereitschaft in
alltaeglicher Sprache. Protokollnamen, Geraetekennungen und technische
Diagnosedaten werden nicht angezeigt. Transportantwort und App-Annahme
bleiben inhaltlich getrennt; nur die App-Annahme wird als Bestaetigung des
Displays bezeichnet. Eine Abbiegevorschau sowie Karten und abgestimmte
Hell-/Dunkelfarben strukturieren die Bedienung.
Ein v1-Testhinweis gilt 5 Sekunden; die Firmware muss danach die Anzeige
beenden. Ein erfolgreicher ACK bestaetigt die Modellannahme, nicht Pixel.

Die **Firmware wurde hier nicht geaendert**. Auf der bisherigen Firmware
bleibt A1 mit FF00/FF01 verfuegbar. Die Oberflaeche weist bei diesen Displays
auf das manuelle Beenden und die fehlende Anzeigebestaetigung hin.
Dieser Weg bekommt durch das App-Update keinen Handshake, keine
Annahmebestaetigung und keine Ablaufgarantie. Ein vorhandener, aber fehlerhafter
v1-Service fuehrt nicht zu einem stillen Legacy-Fallback.

Fuer die separate Firmware-Implementierung:

- [Verbindliche BLE-v1-Spezifikation mit Byte-Fixtures](docs/protocol/PILLAR_ONE_BLE_V1.md)
- [Firmware-Auftrag mit Integrationsstellen und Abnahmekriterien](docs/PILLAR_ONE_FIRMWARE_A2.md)

Die A2-Firmware ist laut Nutzer inzwischen separat implementiert. Der
A2-Hardware-Nachweis ist am 05.10.2026 vom Nutzer bestaetigt: HELLO_ACK,
sichtbarer Testhinweis mit App-ACK, TTL-Ablauf, NAV_END und Wiederaufnahme
beim erneuten Oeffnen der App. Wiederverbindung nach Display-Neustart ohne
erneutes Oeffnen der App ist der folgende Ausbau.

Nach einem Verbindungsabbruch einer zuvor bereiten Sitzung versucht die
Service-Sitzung bis zu dreimal, dasselbe weiterhin gekoppelte Display erneut
zu verbinden. Wartezeiten: 2, 5 und 10 Sekunden zwischen den Versuchen.
Connect-/Discovery-Timeouts gelten zusaetzlich. Jeder Versuch erzeugt eine
frische Sitzung und fuehrt erneut Service-Erkennung und Handshake durch.
Keine Wiederholung von Navigationsbefehlen oder alten Testhinweisen.

Manuelles Trennen (UI oder Notification), ausgeschaltetes Bluetooth oder fehlende
Berechtigungen stoppen die Versuche. Home und Sperren stoppen sie nicht.
Explizites Trennen sperrt auch ausstehende Service-Starts und automatische
Wiederherstellung beim erneuten Oeffnen auch nach Prozessneustart; erst bewusstes
Verbinden hebt die Sperre auf. Kopplungs-/Protokollfehler,
fehlende Services, Schreibfehler und fachliche Ablehnungen werden nicht
automatisch wiederholt. Nach erschoepftem Budget manuell verbinden; eine
erfolgreiche Sitzung setzt das Budget zurueck. Nach endgueltigem Fehler endet
der Foreground Service; keine unbegrenzte Hintergrund-Wiederverbindung.

Validierung dieses Hintergrund-Ausbaus mit Android-Studio-JBR:
`./gradlew :app:testDebugUnitTest :app:assembleDebug
:app:compileDebugAndroidTestKotlin --offline`: 82 Unit-Tests bestanden,
einschliesslich 8 Lebensdauer-/Hintergrund-Reconnect-Tests. Drei neue
Service-/Notification-Instrumentationstests wurden nur kompiliert. Keine
Installation, kein Firmware-Upload und keine neue Hardware-Abnahme.

Die langfristige Planung und SDK-Auswahl stehen in
[docs/ANDROID_APP_START.md](docs/ANDROID_APP_START.md).
