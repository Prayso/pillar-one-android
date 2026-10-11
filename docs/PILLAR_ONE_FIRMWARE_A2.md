# Pillar One: separater Firmware-Auftrag fuer A2

Stand: 04.10.2026. **Anleitung, keine implementierte Firmware-Aenderung.**
Firmware-Projekt: `/Users/pascal/development/Pillar One`.
Android-Projekt: `/Users/pascal/development/pillar-one-android`.

Der OnePlus-Test fuer A1 ist vom Nutzer bestaetigt: Verbinden, sichtbarer
Testhinweis `Rechts / 200 m` und anschliessend `OFF` funktionieren.
A2 erweitert den Austausch um eine echte App-Sitzung, fachliche
Annahmebestaetigungen, Fragmentierung und Ablauf veralteter Hinweise.

## Verbindlicher Vertrag

Gemeinsame Spezifikation:
[protocol/PILLAR_ONE_BLE_V1.md](protocol/PILLAR_ONE_BLE_V1.md).
Sie ist die Quelle fuer UUIDs, Header, Byte-Reihenfolge, Statuscodes, Limits
und Testdaten. Dieses Dokument beschreibt die Integration in die vorhandene
Firmware. Nicht ein zweites, abweichendes Protokoll daraus ableiten.
Fuer die separate Firmware-Arbeit BEIDE Dateien bereitstellen und die
Spezifikation bei Bedarf nach `docs/protocol/` im Firmware-Projekt kopieren.
Aenderungen am Vertrag bewusst mit der Android-Seite abstimmen.

## Auftrag zum Kopieren in die Firmware-Entwicklung

> Implementiere den Pillar-One-App-Vertrag v1 aus
> `PILLAR_ONE_BLE_V1.md` im vorhandenen ESP32-/PlatformIO-Projekt.
> Ergaenze im Phone-Manager einen eigenen 128-Bit-GATT-Service mit
> Write-with-Response-Command und Notify-Response samt CCCD. Implementiere
> HELLO/HELLO_ACK, begrenzte Fragmentassemblierung bei MTU 23,
> validierte NAV_UPDATE/NAV_END, passende ACKs, Sitzungs-/Sequenzpruefung,
> Duplikaterkennung und Ablauf der Navigation ohne Handy.
> Bewahre FF00/FF01, HID, AMS und OBD. Schuetze die v1-Schnittstelle durch
> verschluesselte, gebondete Verbindung und bewusst bestaetigte
> Ersteinrichtung. MAC oder Sitzungs-ID allein gelten nicht als Freigabe.
> Trenne Transport, Protokoll und UI; keine LVGL-Aufrufe in BLE-Callbacks.
> Nutze die vorhandenen Hosttests fuer die reine Protokolllogik, pruefe den
> Firmware-Build und dokumentiere noch offene Hardware-Abnahmen.
> Kein Navigations-SDK, kein OTA, keine Fahrtenaufzeichnung.

## Vorhandene Integrationsstellen

| Stelle | Aufgabe |
| --- | --- |
| `src/main.cpp` | Gemeinsame NimBLE-Initialisierung und bestehende Security-Konfiguration; keine zweite BLE-Instanz anlegen |
| `src/phone_manager.cpp`, `initPhoneManager()` | App-Service vor `g_pServer->start()` ergaenzen |
| `setupAdvertising()` | Neuen Service sichtbar machen, Advertising-Groessen beachten |
| `PhoneServerCallbacks` | Verbindungshandle, Authentifizierung, Trennen und Sitzungsende |
| `phoneLoop()` | Begrenzte Ereignisverarbeitung, Assembly-Timeout und Hinweisablauf |
| `forgetPairedPhone()`, `disconnectPhone()` | V1-Sitzung und Anzeige sicher beenden |
| `src/phone_manager.h`, `PhoneNavData` | Numerische Navigation, getrennte v1-Metadaten und thread-sicherer Snapshot |
| `src/pages/MapsScreen.cpp` | Enum-Uebersetzung, Strassenname, Kreisverkehr-Ausfahrt und Demo-Konflikt |
| `test/host/` | Vorhandene Hosttest-Infrastruktur fuer Codec/Zustandsautomat |

Die vorhandenen Aenderungen in `src/app_config.h` und
`docs/APP_ROADMAP.md` nicht zuruecksetzen oder ungefragt ueberschreiben.

## 1. BLE-Service und Advertising

Die drei UUIDs exakt aus der Spezifikation uebernehmen. Command verlangt
Schreiben mit Antwort; Response verwendet Notify. NimBLE erzeugt fuer eine
Notify-Characteristic normalerweise die CCCD, also nicht blind doppelt anlegen.
Mit der tatsaechlich installierten NimBLE-Arduino-2.5.x-API pruefen.
Notify nur an den zustaendigen Connection Handle senden, nicht an alle Peers.
Handshake nur beantworten, wenn dieser Peer Notifications abonniert hat.

Bestehenden FF00/FF01-Dienst NICHT umdeuten, umbenennen oder entfernen.
Auch HID 0x1812, Batterie-/Device-Info und AMS-Client bleiben erhalten.
Android waehlt v1, wenn dessen Service vorhanden ist; bei defektem v1 gibt
es absichtlich keinen stillen Rueckfall zum alten Testdienst.

Legacy-Advertising und Scan-Response haben jeweils nur 31 Bytes. Eine
zusaetzliche 128-Bit-UUID passt nicht einfach in das heutige Hauptpaket.
HID + FF00 im Hauptpaket bewahren; den neuen v1-Service beispielsweise in
die Scan-Response aufnehmen und deren vorhandene 16-Bit-Service-Liste
gegebenenfalls neu aufteilen. Flags, Appearance und Name ebenfalls
mitzaehlen. Die heutige explizite `setAdvertisementData()`-Konfiguration
darf die vorher hinzugefuegte v1-UUID nicht wieder entfernen.
Mit echten Scanergebnissen pruefen, dass sowohl A1 als auch A2 sichtbar sind.

## 2. Verbindung und bewusste Ersteinrichtung

Die aktuelle Firmware konfiguriert Bonding und Secure Connections global,
aber der Phone-Manager speichert in `onConnect()` unmittelbar eine MAC.
Das beweist noch keine gebondete, zugelassene App-Verbindung.
Fuer v1 daher die Freigabe ausdruecklich implementieren:

- Einen einzigen zugelassenen Phone-Connection-Handle verwalten.
- Neue Erstverbindung nur waehrend lokal am Display gestarteter Kopplung
  zulassen und nach erfolgreicher Verschluesselung/Bonding bestaetigen.
- Ausserhalb der Ersteinrichtung vorhandene Bond-/Peer-Identitaet pruefen.
  Auf Privacy-/Identity-Adressen achten, nicht rohe wechselnde Scan-MACs
  als dauerhaften Identitaetsnachweis vergleichen.
- Die passende NimBLE-Encrypted-Write-Eigenschaft fuer Command verwenden
  und Bonding/Admission zusaetzlich beim HELLO pruefen.
- Session-ID und die 8-Byte-Geraete-ID sind keine Geheimnisse und keine
  Berechtigungsnachweise.

Globale Security-Einstellungen nicht unbemerkt aendern: Sie betreffen auch
OBD/HID/AMS. `NO_INPUT_OUTPUT` bedeutet nicht MITM-geschuetzte numerische
Bestaetigung; keine solche Zusage machen. Die lokale Kopplungsfreigabe und
deren Grenzen dokumentieren. Android muss den normalen
System-Kopplungsdialog auf dem OnePlus durchlaufen koennen.

Die stabile 8-Byte-Device-ID einmal zufaellig erzeugen, gegen Null pruefen
und in eigenem Preferences-Namensraum persistieren. `forgetPairedPhone()`
loescht bisher den gesamten Namensraum `phone`; dort gespeicherte Device-ID
wuerde unbeabsichtigt wechseln. Identitaet weder bei Neustart noch beim
Vergessen eines Handys aendern. Ein bewusstes Factory Reset darf sie erneuern.

## 3. Reiner Codec und begrenzter Zustandsautomat

Empfohlen: kleine Android-unabhaengige C++-Dateien wie
`src/app_protocol.h/.cpp` fuer Header, Reassembly, UTF-8-Pruefung und
Sitzungsregeln. Keine Arduino-Strings oder ungepruefte dynamische
Speichervergroesserung fuer empfangene Daten.

Header 12 Bytes, Fragment maximal 8 Bytes, Message maximal 192 Bytes und
maximal 24 Fragmente. Werte immer einzeln little-endian lesen/schreiben;
keine gepackten Structs mit plattformabhaengiger Ausrichtung versenden.
Laengen vor jedem Zugriff pruefen. UTF-8 erst nach vollstaendiger Assembly
validieren, weil Zeichen ueber Fragmentgrenzen verteilt sein duerfen.

Die Assembly nach 2 Sekunden seit dem ERSTEN Fragment verwerfen. Fehlerhafte
Fragmentfolge aendert keine Navigation. Nicht-finales Kurzfragment, falsche
Session/Sequenz/Anzahl, Ueberlauf und zu grosser Frame werden abgelehnt.
Eine neue Index-0-Nachricht ersetzt nur die unvollstaendige Assembly.

HELLO_ACK und ACK koennen selbst fragmentiert sein. Ihre Frames geordnet
ausgeben, niemals zwei Antworten ineinander verschachteln. TX-Puffer und
Queue strikt begrenzen. Notify-Fehler und fehlende Subscription melden;
keine Sitzung als erfolgreich behandeln, wenn ihre Antwort nicht gesendet
werden kann. Einen vollen Verarbeitungspuffer mit BUSY behandeln, soweit
eine gueltige komplette Anfrage identifizierbar ist; sonst verwerfen/loggen.

Sequenzen und Duplikate EXAKT nach Spezifikation behandeln. Insbesondere
erneuert ein identisches NAV_UPDATE-Duplikat NICHT die Gueltigkeit. Bei
gleicher Sequenz und anderer Nutzlast kein erneutes Anwenden. Neuere
abgelehnte Requests verbrauchen ihre Sequenz; alte Requests duerfen nie
eine neuere Anzeige ersetzen.

## 4. Callback-/Loop-Grenze und fachliche ACKs

NimBLE-Callbacks, Loop und LVGL-Timer koennen auf verschiedenen Tasks laufen.
Bestehende globale `g_nav_data` ohne Schutz nicht als Vorlage fuer neue
gemeinsame Daten uebernehmen. Einen klaren Besitz-/Synchronisationsweg
waehlen, etwa eine kleine begrenzte Ereignisqueue in `phoneLoop()` mit
geschuetztem Snapshot fuer `getPhoneNavData()`.

Connection Handle PLUS Sitzungsgeneration mit Ereignissen speichern.
Nach Disconnect duerfen alte Callback-/Queue-Ereignisse die neue Sitzung
nicht aktualisieren. Das Trennereignis darf nicht an einer vollen normalen
Nachrichtenqueue haengen bleiben; sofort invalidieren und sicher loeschen.
Keine blockierenden BLE-Aufrufe unter lang gehaltenem Mutex oder Critical
Section. Keine LVGL-Aufrufe aus dem BLE-Callback.

ACK OK erst senden, nachdem Navigation vollstaendig validiert und atomar
in den von der Anzeige gelesenen Modellzustand uebernommen wurde.
GATT Write Response bestaetigt nur den Transport. Modell-Akzeptanz ist
weiterhin kein Beweis der tatsaechlich sichtbaren Pixel.

## 5. Navigation und Darstellung

V1 nicht durch den alten Pipe-/JSON-/Notification-Textparser schicken.
Numerische Entfernung und Enum direkt validieren. Instruction maximal
96 UTF-8-Bytes und street maximal 64 Bytes getrennt speichern, jeweils mit
zusaetzlichem Platz fuer einen C-String-Terminator. Eine kombinierte
Instruction+Street passt nicht generell in das heutige 128-Byte-Feld:
nicht still abschneiden.

Die vorhandenen Manoeverzahlen sind NICHT v1-kompatibel:

| V1 | Bestehende Anzeige |
| --- | --- |
| Geradeaus 0, links 1, rechts 2 | Gleiche Zahl |
| Kreisverkehr 3 + exit 1..16 | Bisher 3..6 fuer nur vier Ausfahrten |
| Wenden 4 | Bisher 7 |
| Ankunft 5 | Bisher kein eigener Typ |
| Links halten 6, rechts halten 7 | Bisher andere Bedeutung |

Eigenes v1-Modell bzw. ausdrueckliche Adapter verwenden. Legacy-Demo und
FF01 duerfen ihre bisherigen Zahlen behalten. Alle v1-Ausfahrten bis 16
darstellbar machen, statt auf die alte 4-Ausfahrten-Grenze zu reduzieren.
Den Titel `Google Maps` fuer app-eigene Navigation neutralisieren, ohne
eine Anbieterintegration vorzutaeuschen.

Die Demo in `MapsScreen.cpp` schreibt derzeit regelmaessig in denselben
Navigationszustand. Keine laufende v1-Sitzung durch Demodaten ueberschreiben
lassen. Beim Ende/Ablauf einer v1-Route nicht automatisch eine alte
Legacy-/Demo-Route wieder anzeigen.

TTL akzeptiert 1..30 Sekunden; fuer Androids Test mindestens 5 Sekunden
advertisieren. Mit `uint32_t(now - acceptedAt) >= ttlMs` ablaufen lassen,
damit `millis()`-Ueberlauf funktioniert. TTL nur bei tatsaechlichem Commit
starten, nicht beim ersten Fragment, GATT-Empfang oder ACK-Wiederholung.
In `phoneLoop()` pruefen, auch wenn die Navigationsseite nicht geoeffnet ist.

NAV_END, Disconnect, vergessenes Handy und neue Sitzung deaktivieren v1
und leeren deren Texte/Distanz/TTL. Legacy-Verhalten nicht pauschal aendern,
aber alte Werte bei einem Wechsel in v1 nicht wiederbeleben.

## 6. Hosttests, Build und Hardware-Abnahme

Vorhandene Testwerkzeuge in `test/host/` verwenden; keine zusaetzliche
Testplattform nur fuer diese Aufgabe einfuehren. Die vorhandenen
`sh test/host/run_tests.sh` und `sh test/host/run_manager_tests.sh` decken
bisher Fahrzeug-/OBD-Logik ab, nicht den neuen App-Vertrag. Den reinen
Protokolltest in diese kleine C++17-/POSIX-Shell-Infrastruktur integrieren,
statt bestehende OBD-Tests als Protokollnachweis auszugeben.
Mindestens nachweisen:

| Fall | Erwartung |
| --- | --- |
| Gemeinsame HELLO-, Update-, ACK-, End-Fixtures | Bytegenau zur Android-Spezifikation |
| MTU 23, zwei Update-Fragmente | Erst nach vollstaendiger Message anwenden |
| UTF-8 ueber Fragmentgrenze | Gueltige Zeichen erhalten, ungueltige Bytes ablehnen |
| Header-/Laengen-/Enum-/TTL-/Exit-Fehler | Keine Modellmutation, passende Ablehnung |
| Fehlendes, vertauschtes, zu spaetes Fragment | Assembly verworfen, vorherige TTL nicht erneuert |
| Gleiche Anfrage doppelt | Gleicher ACK, keine erneute Anzeige/TTL |
| Alte Sequenz / gleiche Sequenz mit anderem Inhalt | STALE_SEQUENCE / INVALID_PAYLOAD |
| Ablauf, auch ueber millis-Ueberlauf | Route aus und Texte leer |
| Disconnect mit ausstehendem Ereignis | Keine alte Navigation in neuer Sitzung |
| Wiederverbindung | Neuer HELLO, keine alten Requests nachliefern |

Danach vorhandenes ESP32S3-Buildziel bauen, zum Beispiel `pio run -e esp32s3`
mit der vorhandenen PlatformIO-Installation. Flashen bewusst getrennt vom
Build durchfuehren und das richtige USB-Geraet waehlen.

Hardware-Abnahme mit aktualisierter Android-App und Firmware:

1. Ersteinrichtung/Bonding, dann v1-Handschlag und Geraete-ID pruefen.
2. Test senden: akzeptierter Hinweis `Rechts / 200 m`, danach ohne weiteres
   Senden nach etwa 5 Sekunden keine aktive Route mehr.
3. Test erneut senden und vor Ablauf NAV_END druecken; unmittelbar beendet.
4. App schliessen/Handy sperren oder Funk trennen; v1-Anzeige wird geloescht.
5. Neu verbinden: neue Sitzung, kein automatischer alter Hinweis.
6. Ablehnung/Timeout/kleine MTU und fehlende Subscription bewusst testen.
7. FF00/FF01-A1, OBD, HID-Tasten und AMS mit iPhone als Regression pruefen.

Erst nach diesen Nachweisen A2 als hardwareseitig abgenommen markieren.
Android allein und ein erfolgreicher Firmware-Build sind dafuer nicht genug.
