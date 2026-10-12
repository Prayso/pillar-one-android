# Pillar One acceleration telemetry v1

This extension uses the existing authenticated v1 app session and response
notification characteristic. HELLO_ACK capability bit `0x0008` advertises
acceleration telemetry. Older firmware remains usable for existing features.
Bit `0x0010` additionally advertises automatic page-state/running flags and
page-status heartbeats. Android requires both bits for automatic recording.
Firmware must be built and flashed separately; Android does not provide OTA.

## Unsolicited sample

Message type `ACCELERATION_SAMPLE = 0x30`, current nonzero app session, an
independent uint16 telemetry sequence. Commands and ACK matching do not consume
this sequence. Existing 12-byte v1 frame headers and 8-byte payload fragments
apply. The following 24-byte little-endian payload requires three frames:

| Offset | Size | Meaning |
| --- | --- | --- |
| 0 | 4 | Nonzero random boot ID |
| 4 | 4 | Nonzero measurement run ID; changes on real measurement reset |
| 8 | 4 | Sample `millis()` timestamp, uint32 wraparound |
| 12 | 2 | Real vehicle speed, km/h |
| 14 | 1 | Flags: bit 0 valid speed, bit 1 0-100 ready, bit 2 100-200 ready, bit 3 acceleration page active, bit 4 0-100 running, bit 5 100-200 running |
| 15 | 1 | Reserved, zero |
| 16 | 4 | 0-100 duration in ms, zero unless ready |
| 20 | 4 | 100-200 duration in ms, zero unless ready |

No other flag bits are allowed. Invalid-speed samples may retain only the page
active bit; ready/running bits and durations are zero.
Completed durations must be positive and at most 120 seconds.
No demo samples or extra OBD queries are permitted. Only the connected,
authorized and subscribed app receives data. Response fragment chains must
not interleave with command responses.

This is live telemetry, not a firmware history transfer. A bounded latest-sample
buffer may replace unsent snapshots. Notification failure must be logged.
Firmware sends page-entry/exit status even without valid OBD speed and periodic
status heartbeats. The sample timestamp continues to refer to the actual OBD
sample, not the heartbeat. Page entry/exit resets the real measurement run.
Android requires an observed ready transition within a continuous run and an
active recording service; a previously completed snapshot is not a new
measurement. Disconnect, reboot, invalid data or a gap over three seconds
invalidates the Android car baseline. The persistent result key combines
display ID, boot ID, run ID and interval to prevent duplicate history entries.

After explicit saved consent and precise location permission, Android prepares
a location foreground service while its Activity is visible. The service can
remain alive with a standby notification while BLE is connected; GPS callbacks
are requested only while a fresh acceleration-page-active status is present.
Locked/background operation needs no background location permission when the
service was correctly started while visible. Disabling consent, disconnect or
stale page status stops GPS; process death requires reopening the app.
Repeated sample timestamps in heartbeats must not be treated as new speed data.

Android records reception times against the phone monotonic clock. Firmware
sample timestamps detect continuity and rollover; there is no cross-device
clock synchronization. Car/GPS pairing is therefore approximate and conservative:
only a unique opposite-source result with both start and end within two seconds
is combined. Unmatched car results retain explicit missing GPS information;
GPS-only completions without a corresponding car result are not new records.
Each stored source result includes separate start and end coordinates/accuracy
if a valid GPS fix was available close to that endpoint. Schema v2 migrates v1
history without fabricating historical endpoint coordinates.

## Hardware acceptance

On an authorized test track, enable GPS consent and connect with the app visible,
then open the acceleration page at the display. Verify both
intervals, actual firmware durations against Android, coordinates/accuracy,
date/time, persistence after process restart, invalid/stale GPS, OBD disconnect,
BLE reconnect, no demo imports, denied/revoked location access, page entry/exit
without OBD data, stale heartbeats, one-time consent, notification disable,
locked phone/background operation and service preparation after process restart.
Navigation/menu/media must continue working while telemetry is sent. These
hardware checks are separate from codec, detector, database and UI tests.
