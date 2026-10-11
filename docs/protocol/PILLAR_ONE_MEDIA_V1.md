# Pillar One Media Bridge V1

Stand: 2026-10-11. Optionale Erweiterung des App-Service V1, kein Ersatz fuer
Navigation, Menue, HID oder iOS AMS. Beide Repos halten dieses Dokument identisch.

## GATT und Freigabe

- Service: `9a8b0001-7e4f-4c21-9d63-2b51a670c8e0`.
- Media: `9a8b0005-7e4f-4c21-9d63-2b51a670c8e0`, WRITE | WRITE_ENC | NOTIFY.
- Optional Platform: `9a8b0004-7e4f-4c21-9d63-2b51a670c8e0`, WRITE | WRITE_ENC.
  Android schreibt nach erfolgreichem HELLO_ACK genau ein Byte `02` (binaer).
- Android entdeckt die optionalen Characteristics, wartet auf READY, schreibt
  Platform und aktiviert danach Media-Notifications bei freigegebenem,
  verbundenem Notification Listener. Fehlende Erweiterungen verhindern keine
  Navigation mit alter V1-Firmware; Legacy FF00/FF01 bleibt unveraendert.
- Firmware verlangt den aktuellen zugelassenen Peer, verschluesselt und gebondet,
  passende aufgeloeste Identitaet samt Adresstyp und Media-CCCD-Subscription.
  Die Freigabe stammt aus dem vorhandenen lokalen Pairing-/NVS-Verfahren.
  NAV_END oder Abmeldung der V1-Response-Subscription beenden die Bridge nicht.
- Android serialisiert alle GATT-Writes und Descriptor-Writes. Kein Media-Frame
  wird in einen Navigation-/Menueaustausch eingeschoben. Ein laufendes Media-Paket
  wird fertiggeschrieben; weitere Updates werden zum neuesten Zustand zusammengefasst.
- GATT-Schreibbestaetigung bedeutet nicht Annahme von Metadaten oder Ausfuehrung
  eines Medienbefehls. Die Bridge hat keine Application-ACKs und keine Retries
  von Steuerereignissen. Ein mehrdeutiger GATT-Timeout schliesst die App-Verbindung.

## Metadaten: Android nach Firmware

Jeder Write ist 8 bis 20 Bytes lang, auch bei ATT-MTU 23. Header:

| Offset | Bytes | Inhalt |
| --- | --- | --- |
| 0 | 1 | Version `01` |
| 1 | 1 | Flags: Bit 0 playing, alle anderen Bits 0 |
| 2 | 2 | messageId, uint16 little endian |
| 4 | 1 | fragmentIndex, ab 0 |
| 5 | 1 | fragmentCount, 1 bis 11 |
| 6 | 1 | titleByteLength, 0 bis 63 |
| 7 | 1 | artistByteLength, 0 bis 63 |
| 8 | 0 bis 12 | Payload-Fragment |

Payload ist UTF-8 `title + artist`, ohne Terminator, insgesamt maximal 126 Bytes.
Android kuerzt beide Felder separat an einer Codepoint-Grenze und entfernt NUL.
Firmware validiert beide UTF-8-Felder separat: keine NUL, unvollstaendigen oder
ueberlangen Sequenzen, Surrogate, isolierten Continuation-Bytes oder Werte ueber
U+10FFFF. Ein Zeichen darf die Fragmentgrenze, nicht aber die Feldgrenze kreuzen.

fragmentCount = max(1, ceil(totalBytes / 12)). Nichtletzte Fragmente enthalten
genau 12 Payload-Bytes; das letzte exakt die verbleibenden Bytes. Alle Headerfelder
ausser fragmentIndex bleiben gleich, Indizes sind lueckenlos aufsteigend.
Ein neues Index-0-Frame ersetzt eine unvollstaendige Assembly. messageId ist ein
Paketbezeichner, kein globaler Frische-/Replay-Schutz, und darf modulo 65536 laufen.

Keine Session: ein Header-only-Frame mit Flags 0, Laengen 0, Index 0, Count 1.
Leere einzelne Metadatenfelder bleiben leer; fehlende Titel werden nicht erfunden.
Ein aktiver Player kann auch ohne Titel/Artist seinen echten playing-Status melden.
Der Firmware-Titelplatzhalter bleibt bei leerem Titel `Musikwiedergabe`.

Beispiel Titel A, Artist B, playing, messageId 0x1234:
`01 01 34 12 00 01 01 01 41 42`.
Keine Session, messageId 0: `01 00 00 00 00 01 00 00`.

Firmware-Callbacks kopieren begrenzt in eine Queue mit 16 Eintraegen. Verarbeitung
und Publikation erfolgen in phoneLoop, nicht als UI-Schreibzugriff im BLE-Callback.
Link- und Bridge-Generationen verhindern alte Queue-/Assembly-Daten nach Reconnect
oder Abmeldung. Assembly-Deadline: 2000 ms ab Empfang des ersten Fragments, nicht
ab letzter Aktivitaet oder Verarbeitung; auch verzoegerte Queue-Verarbeitung zaehlt.
Fehler, Luecken, Queue-Ueberlauf und schlechte UTF-8-Pakete erhalten den letzten
vollstaendig gueltigen Zustand und erneuern seine Gueltigkeit nicht.

Android sendet bei Aenderungen und alle 5000 ms den aktuellen Zustand waehrend
der gestarteten Foreground-Service-Sitzung, auch nach Home oder Screen-off.
Nach 15000 ms ohne vollstaendiges gueltiges Update loescht Firmware Artist und
playing und setzt den Titelplatzhalter. Disconnect und Media-Abmeldung loeschen
Queue/Assembly und setzen Metadaten zurueck; Abmeldung wird in phoneLoop publiziert.

## Steuerereignisse: Firmware nach Android

Notification exakt 4 Bytes: `01 action commandIdLo commandIdHi`.

| Action | Bedeutung |
| --- | --- |
| 1 | Play/Pause umschalten |
| 2 | Naechster Titel |
| 3 | Vorheriger Titel |
| 4 | Lautstaerke erhoehen |
| 5 | Lautstaerke verringern |

commandId steigt pro Steuerereignis modulo 65536 und wird beim Link-Ende
zurueckgesetzt. Android verarbeitet Controls unabhaengig von V1-Response-Exchanges,
validiert Version/Laenge/Action und ignoriert doppelte/rueckwaertige IDs (Vorwaerts-
Delta 1..32767). Die Deduplizierung wird beim App-GATT-Verbindungsende geloescht.

Firmware verwendet bei zugelassenem sicherem Peer mit expliziter Bridge-Subscription
nur die gezielte Media-Notification an den aktuellen Handle und die aktuelle
Link-/Bridge-Generation. Sonst bleibt der vorhandene Consumer-Bit-HID-Pfad erhalten.
Bridge-Notify-Fehler werden geloggt, nie als HID erneut gesendet. Bridge-Play/Pause
aendert playing nicht optimistisch; nur empfangene Metadaten bestimmen diesen Wert.
Ein erfolgreiches notify bestaetigt nicht, dass Android oder der Player es ausfuehrt.

## Android-Quelle und Lebenszyklus

MediaSessionListener ist ein vom Nutzer freizugebender NotificationListenerService.
Er registriert MediaSessionManager und MediaController-Callbacks ohne Activity-
Eigentuemerschaft. Er liest keine Benachrichtigungstexte und erfindet keine Titel.
Aktiv spielende/buffernde/verbindende Sessions haben Prioritaet; bei Gleichstand
bleibt der bisherige Controller ausgewaehlt, sonst eine vorhandene pausierte Session.
Metadata-/Playback-Callbacks, Session-Ende, Listener-Verlust und Freigabeentzug
aktualisieren einen StateFlow. Ohne Session oder Zugriff wird leerer Zustand publiziert.

Controls gehen an MediaController.TransportControls: play/pause entsprechend
Wiedergabezustand, skipToNext/skipToPrevious nur bei angebotener Action. Volume
nutzt adjustVolume nur bei RELATIVE/ABSOLUTE-Unterstuetzung; FIXED wird ignoriert.
Fehlende Session oder nicht unterstuetzte Aktionen werden nicht durch HID wiederholt.

BLE gehoert der gemeinsamen Application-Runtime, nicht dem ViewModel. Eine
Verbindung wird nur aus der sichtbaren App mit vorhandener Bluetooth-Freigabe
angefordert. BleConnectionService wird mit startForegroundService gestartet und
vor GATT-Aufbau als Foreground Service vom Typ connectedDevice bekannt gemacht.
Er besitzt den Media-StateFlow-Collector; der Controller aktualisiert Metadaten
weiterhin alle 5 Sekunden. UI und Service verwenden genau einen Controller.
Home, Activity-Ende, Entfernen aus der App-Uebersicht und Screen-off beenden eine
gestartete Sitzung nicht (stopWithTask=false). Auch ein bereits aus der sichtbaren
App angeforderter, noch nicht zugestellter Service-Start darf danach fortfahren.
Scans enden beim Verlassen der UI; es gibt keine Hintergrundscans.

Die dauerhafte, leise Bluetooth-Benachrichtigung oeffnet die App und bietet
Trennen. Ab Android 13 wird POST_NOTIFICATIONS beim Nutzer-Verbindungsablauf
angefragt. Ablehnung verhindert die Verbindung nicht: Die Foreground-Service-
Benachrichtigung bleibt Pflicht, ist dann aber nur in Androids Task Manager
statt im Benachrichtigungsbereich sichtbar. Medienzugriff ist separat freizugeben.

UI-Trennen und Notification-Trennen sperren sofort Wiederverbindung und
Wiederherstellung, auch fuer ausstehende Service-Starts. Erneutes Oeffnen der App
hebt die gespeicherte Sperre auch nach Prozessneustart nicht auf; nur bewusstes
Verbinden tut das.
Normaler Disconnect wartet auf den laufenden serialisierten Austausch und
deaktiviert die Media-CCCD vor GATT-close und Service-Ende, damit ein verbleibender
OS-HID-Link wieder HID verwenden kann. Bei Transportabbruch, Timeout, Prozessende
oder erzwungenem Stop ist diese Abmeldung nicht garantiert; Metadaten laufen ab.

Eine zuvor bereite Sitzung versucht bei unerwartetem Verbindungsverlust auch
im Hintergrund hoechstens drei Reconnects (2/5/10 Sekunden), mit frischem Handshake
und ohne alte Navigationsbefehle. Berechtigungsverlust, Bluetooth aus, fehlender
Bond, Protokollfehler oder erschoepfte Versuche beenden den Service. Endgueltige
Fehler lassen keinen leeren Foreground Service zurueck. Aeltere Firmware ohne
Bridge behaelt den bestehenden Legacy-/HID-Pfad.

START_NOT_STICKY: kein automatischer Prozess-/Service-Neustart, kein Boot-Receiver
und keine neue Verbindung aus dem Hintergrund nach Prozessende. Android-Force-
Stop und Task-Manager-Stop beenden den Betrieb; bewusstes spaeteres Oeffnen kann
ein gespeichertes Display wiederherstellen, sofern nicht explizit getrennt wurde.
OnePlus/OxygenOS kann den Prozess
zusaetzlich beenden. Bei Problemen die App-Akkueinstellung fuer Hintergrundbetrieb
bzw. uneingeschraenkte Nutzung bewusst pruefen. Keine automatische Ausnahme von
Akkuoptimierung und kein unbegrenzter Wake-Lock. Der Service verfolgt keinen
Standort; echte Hintergrundnavigation bleibt eine separate Erweiterung.

## Android-Diagnose und begrenzte Recovery

Die bestehende Medienzeile im Display-Bildschirm ist der Diagnoseplatz: Sie zeigt
Listener-Zugriff und Verbindung getrennt, echte Player-Titel/Artist, erkannte
Media-UUID/CCCD sowie Ablehnungsgrund und numerischen GATT-Status. Keine Titel
werden erfunden; fehlende Firmware oder fehlender Nutzerzugriff sind moegliche
Ursachen, ohne Geraet aber nicht bewiesen. HID-Kompatibilitaet ist nicht abgenommen.

| Anzeige/Befund | Bedeutung und naechster Diagnosepunkt |
| --- | --- |
| Medienzugriff nicht freigegeben | Notification-Listener-Freigabe fehlt; vorhandener Freigabe-Button oeffnet die Systemeinstellung. |
| Zugriff freigegeben, Listener getrennt | Freigabe allein ist keine aktive Service-Verbindung; hoechstens 3 requestRebind-Aufrufe, mindestens 15 s Abstand, kein endloser 5-s-Rebind. |
| Keine aktive Mediensitzung | Listener verbunden, aber kein MediaController; Titel/Artist bleiben leer. |
| Firmware ohne Media-Characteristic | UUID 0005 wirklich nicht entdeckt; aeltere V1-/Legacy-Firmware kann Navigation weiter anbieten. |
| Eigenschaften/CCCD fehlen | UUID gefunden, aber WRITE/NOTIFY oder CCCD fehlen; permanent fuer diese GATT-Sitzung, keine Resubscribe-Retries. |
| Medienverbindung wird vorbereitet | Lokale Notification-Registrierung/CCCD-Write laeuft; noch keine bestaetigte Subscription. |
| Abgelehnt, Wiederholung ausstehend | Eindeutige lokale Write-Ablehnung oder Callback-Fehler; Grund und Status unterscheiden Registrierung, CCCD und Metadaten. |
| Medienverbindung fehlgeschlagen | 3 Retries erschoepft oder Security-Fehler; Authentication/Authorization/Encryption verlangen explizite sichere Wiederverbindung, Bluetooth-Freigabeverlust schliesst GATT. |
| Bruecke aktiv, Metadaten per GATT uebertragen | Erfolgreicher CCCD-Callback und alle Frames eines Metadatenpakets GATT-bestaetigt; keine Application-ACK/Firmware-Annahmebestaetigung. |

Zustaende in BleState: mediaBridgeStatus (IDLE, MISSING_CHARACTERISTIC,
UNSUPPORTED, SUBSCRIBING, RETRY_WAIT, FAILED, ACTIVE), mediaBridgeError,
mediaBridgeGattStatus, mediaBridgeRetryCount, mediaCharacteristicFound,
mediaCccdFound und lastMetadataGattCompleteAt (monotone Android-Uptime in ms,
null bis zum ersten vollstaendig GATT-bestaetigten Metadatenpaket). Listener:
accessGranted, connected, playerPackage, rebindAttempts und listenerError.

Eindeutige optionale Ablehnungen behalten den Medienwunsch und den neuesten
Snapshot. Erst der 5-s-Heartbeat erlaubt einen Retry, maximal 3 nach dem ersten
Fehler; es gibt keine rekursive Retry-Schleife im GATT-Callback. Nach partieller
Metadaten-Ablehnung startet das neueste Paket mit neuer messageId bei Index 0.
Erfolgreiche Subscription bzw. vollstaendige Metadatenuebertragung und Reconnect
setzen den zugehoerigen Fehler-/Retry-Zustand zurueck. Deaktiviert/getrennt werden
keine Metadaten wiederholt. Abmeldung bei explizitem Trennen wird einmal versucht;
auch eine sofortige Ablehnung beendet den Disconnect. Alle Ablehnungspfade geben
Descriptor-/Write-/busy-Zustaende frei. Mehrdeutige Timeouts schliessen GATT ohne
Retry. Controls werden nie erneut gesendet und nie durch HID dupliziert.

Logs PillarOneBLE/PillarOneMedia: erkannte Characteristics/CCCD, Listener enabled/
connected/session, CCCD lokaler Write-Status und Callback-Status, messageId,
Frameanzahl/-laengen und Titel-/Artist-Byte-Laengen, vollstaendiges GATT-Paket,
sowie empfangene Control-ID/Action und Dispatch-Ergebnis. Heartbeats loggen keine
vollstaendigen Titel. Fuer einen Fehlerbericht Medienzeile, obige Zustandsfelder,
Firmwarestand und diese Logs angeben; fehlendes ADB verhindert Hardwarebeweise.

## Verifikation und offene Hardware-Abnahme

- Firmware: `sh test/host/run_phone_tests.sh` (48 Tests, davon 5 Bridge-Gruppen)
  und `sh test/host/run_protocol_tests.sh` (13 Gruppen) bestanden.
- Android mit Android-Studio-JBR: `./gradlew :app:testDebugUnitTest
  :app:assembleDebug :app:compileDebugAndroidTestKotlin --offline` bestanden.
  97 Unit-Tests, davon 15 neue Recovery-/Metadaten-Queue-/Listener-Policy-Tests,
  5 Media-Codec-/Control-Deduplizierungstests und 8
  Lebensdauer-/Hintergrund-Reconnect-Tests.
- Drei neue Service-/Notification-Instrumentationstests sowie die bestehenden
  Instrumentationstests wurden nur kompiliert, nicht auf Hardware ausgefuehrt.
  Host-/Codec-Tests beweisen keine Android-MediaSession- oder BLE-Funkfunktion.
  Die neuen JVM-Tests verwenden die im Controller/Listener eingesetzten Policies
  und die echte Metadaten-Queue; sie simulieren keine Android-GATT-Callbacks.
- Offen: Nutzerfreigabe auf OnePlus, echte Titel/Artist/Play-Pause/Skip/Volume in
  unterstuetzten Playern, Wechsel zwischen mehreren Sessions, keine Session,
  Freigabeentzug, MTU 23 mit langen Unicode-Feldern, Notify-Fehler ohne Doppelaktion,
  Home/Activity-Ende/App-Uebersicht/Screen-off mit laufender Musik und 5s-Heartbeat,
  Pending-Start-Abbruch, Trennen aus UI/Notification ohne Restore bei Resume,
  Bluetooth aus/Berechtigungsentzug, begrenzter Hintergrund-Reconnect, Service-Ende
  bei finalem Fehler, abgelehnte Notification-Freigabe, OxygenOS-Energiesparen,
  Force-Stop/Task-Manager-Stop, OS-HID-Link nach Trennen, iOS-AMS-/HID-/OBD-Regression.
- Keine Commits, Installationen oder Firmware-Uploads fuer diese Implementierung.