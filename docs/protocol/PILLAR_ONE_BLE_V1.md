# Pillar One BLE App Contract v1

Status: A2 implementation contract, 2026-10-04. Android is prepared against
this document; firmware support must be implemented separately. FF00/FF01
remains an explicitly labelled legacy prototype, not a v1 session.

## GATT service

| Purpose | UUID | Properties |
| --- | --- | --- |
| App service | `9a8b0001-7e4f-4c21-9d63-2b51a670c8e0` | Primary service |
| Command | `9a8b0002-7e4f-4c21-9d63-2b51a670c8e0` | Write with response |
| Response | `9a8b0003-7e4f-4c21-9d63-2b51a670c8e0` | Notify, CCCD 0x2902 |

Advertise the app service during the existing pairing/advertising workflow.
Android also scans for FF00 for compatibility. Discover v1 first; use legacy
only when the entire v1 service is absent. A partially implemented v1 service,
failed CCCD setup, rejected handshake or timeout must NOT fall back to legacy.
No new operating-system pairing method is introduced. The server must enforce
its existing paired-phone admission and encrypted/bonded-link requirements.
Session/device IDs are identifiers, not authentication credentials.

## Transport framing

All multibyte integers are unsigned little-endian. Each GATT write/notification
is ONE frame, at most 20 bytes, valid at ATT MTU 23. A larger negotiated MTU
does not change v1 framing.

| Byte offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | Magic `50 31` (ASCII P1) |
| 2 | 1 | Version, exactly 1 |
| 3 | 1 | Message type |
| 4 | 4 | Session ID, nonzero |
| 8 | 2 | Message sequence |
| 10 | 1 | Fragment index, zero-based |
| 11 | 1 | Fragment count, 1..24 |
| 12 | 0..8 | Message payload fragment |

Maximum reassembled payload: 192 bytes. Each non-final fragment has exactly
8 payload bytes; the final fragment has 1..8 bytes. An empty message is one
12-byte frame with index 0/count 1. Never split a frame across writes. UTF-8
code points MAY span fragments; validate UTF-8 only after full reassembly.

Fragments must arrive consecutively from index 0, with identical type,
session, sequence and count. A new index 0 replaces an incomplete assembly.
Any other mismatch discards the assembly without updating navigation.
Assembly expires 2 seconds after its first fragment (not after the last).
Only one incomplete message per connection/direction. Strictly bound buffers.
Malformed headers/unknown sessions are logged and discarded; no success ACK.
Where a valid complete envelope exists, reject invalid content with an error ACK.

After v1 service discovery, Android requires an Android system bond before
subscription/HELLO. It starts system pairing when needed and waits up to
60 seconds; cancellation/failure/timeout closes the app connection.
An existing bond skips this wait, but does not replace server authorization
or the HELLO_ACK. No automatic bond deletion/re-pairing is attempted.
The ordinary GATT timeouts apply only AFTER this pairing step.

Android waits for each GATT write callback before writing the next fragment.
It enables local notifications and waits for the successful CCCD write before
HELLO. GATT write/CCCD timeout: 5 seconds; connect/discovery: 12 seconds each.
Application response timeout: 3 seconds from the final successful write.
Responses can precede the final write callback and must be retained.
A missing/failed transport callback or application response closes the session,
invalidates pending callbacks and clears the operation. No automatic replay.

## Session and message ordering

Android generates a fresh cryptographically random nonzero uint32 session ID
for EACH connection. HELLO uses sequence 0. Commands use strictly increasing
sequences 1..65535; no wrap in a session, reconnect before exhaustion.

| Type | Value | Direction | Payload |
| --- | --- | --- | --- |
| HELLO | 0x01 | App -> display | Empty |
| HELLO_ACK | 0x02 | Display -> app | See below |
| NAV_UPDATE | 0x10 | App -> display | See below |
| NAV_END | 0x11 | App -> display | Empty |
| ACK | 0x80 | Display -> app | Request type, status (2 bytes) |

HELLO_ACK echoes session and sequence 0. Its payload is exactly 15 bytes:
status uint8, stable device ID (8 opaque bytes), capabilities uint16,
maximum message bytes uint16, maximum hint TTL seconds uint16.
Success requires nonzero device ID, capabilities bits 0 and 1,
maximum message bytes in 10..192, TTL in 1..30.
Bit 0 = navigation with expiry; bit 1 = explicit navigation end.
Bit 2 = [existing menu configuration](PILLAR_ONE_MENU_V1.md).
Other capability bits are reserved and ignored by v1 Android.
For rejected HELLO, status is nonzero and the other fields are zero.
The header version remains 1 even when status is UNSUPPORTED_VERSION.

Only after successful HELLO_ACK is the session READY. A GATT connection or
successful write is insufficient. A new valid HELLO on the admitted connection
clears previous session, navigation, sequencing, duplicates and assemblies;
duplicate HELLO for the same session re-sends the same HELLO_ACK without clearing
already accepted navigation. Other connections cannot replace the admitted session.

ACK echoes request session and sequence, and contains exactly:
request type uint8, status uint8. Status values:

| Status | Value | Meaning |
| --- | --- | --- |
| OK | 0 | Fully validated and committed to display navigation model |
| UNSUPPORTED_VERSION | 1 | Protocol version unsupported |
| INVALID_PAYLOAD | 2 | Invalid length, UTF-8, value or reserved field |
| WRONG_SESSION | 3 | HELLO required / session mismatch |
| STALE_SEQUENCE | 4 | Sequence older than last processed request |
| BUSY | 5 | Cannot commit now |
| UNSUPPORTED_COMMAND | 6 | Unknown message type |
| NOT_AUTHORIZED | 7 | Existing link/admission requirements not met |

ACK OK means accepted into the model, NOT proof of pixels on the physical display.
Error ACK must not change navigation or renew its expiry.
Cache the latest processed command's sequence, type, exact payload and response.
An identical duplicate re-sends that response without applying the command or
renewing TTL. Same sequence/different content is INVALID_PAYLOAD. Older sequence
is STALE_SEQUENCE. Newer complete requests, including rejected ones, consume
their sequence and cache their response. Android v1 does not retry commands;
these duplicate rules make later bounded retries interoperable.

## Navigation payload

NAV_UPDATE:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | TTL seconds, 1..min(30, advertised maximum TTL) |
| 2 | 4 | Distance metres, 0..1,000,000 |
| 6 | 1 | Maneuver enum |
| 7 | 1 | Roundabout exit, 1..16 only for roundabout; otherwise 0 |
| 8 | 1 | Instruction UTF-8 byte length, 1..96 |
| 9 | 1 | Street UTF-8 byte length, 0..64 |
| 10 | variable | Instruction then street, no terminator |

Length must match EXACTLY and not exceed the advertised maximum message bytes.
Reject malformed UTF-8, embedded NUL and Unicode control characters U+0000..001F
and U+007F..009F. No silent truncation. Whitespace-only instruction is invalid.
Enums: straight=0, left=1, right=2, roundabout=3, u-turn=4,
arrival=5, keep-left=6, keep-right=7. Other values invalid.

TTL begins ONLY when a complete message is accepted into the model. At expiry,
deactivate navigation and clear instruction/distance. NAV_END immediately
clears navigation and expiry, then ACKs. Disconnect, forgotten pairing and
session replacement also immediately clear v1 navigation. Expiry must run
without an app connection, in the firmware loop with wrap-safe elapsed time.
Normal navigation will refresh the current state before expiry; A2's manual
test deliberately expires after 5 seconds. No heartbeat renews a stale hint.

On reconnect Android does not replay queued/old commands. A future navigation
adapter may send ONLY its current still-valid state after a new handshake.
The A2 prototype requires a new explicit button press.
While visible, Android may reconnect the same still-bonded display after a
previously established connection is lost: at most three attempts, delayed
by 2, 5 and 10 seconds, each with a fresh session/handshake. User disconnect,
backgrounding and security/protocol rejection stop recovery. This is transport
recovery, not command retry. No firmware/protocol-format change is required.

## Fixtures and acceptance

Session `0x12345678`, HELLO:
`50 31 01 01 78 56 34 12 00 00 00 01`

HELLO_ACK OK, device ID `01 02 03 04 05 06 07 08`, capabilities 3,
maximum payload 192 bytes, maximum TTL 30 seconds:

```text
50 31 01 02 78 56 34 12 00 00 00 02 00 01 02 03 04 05 06 07
50 31 01 02 78 56 34 12 00 00 01 02 08 03 00 c0 00 1e 00
```

NAV_UPDATE, sequence 1, TTL 5s, 200m, right, instruction `Rechts`, no street:
payload `05 00 c8 00 00 00 02 00 06 00 52 65 63 68 74 73`.

Frames:

```text
50 31 01 10 78 56 34 12 01 00 00 02 05 00 c8 00 00 00 02 00
50 31 01 10 78 56 34 12 01 00 01 02 06 00 52 65 63 68 74 73
```

ACK:
`50 31 01 80 78 56 34 12 01 00 00 01 10 00`

NAV_END, sequence 2:
`50 31 01 11 78 56 34 12 02 00 00 01`

NAV_END ACK:
`50 31 01 80 78 56 34 12 02 00 00 01 11 00`

At MTU 23 prove handshake, two-fragment test update, acceptance ACK,
5-second expiry, explicit end, malformed/out-of-order fragments, timeout,
rejection, duplicates without expiry renewal, disconnect cleanup and fresh
session after reconnect. Android unit tests and the firmware implementation
must agree on these byte fixtures. Also exercise bonding/admission, OBD and
HID/AMS in parallel. No A2 hardware acceptance until BOTH sides are deployed.
