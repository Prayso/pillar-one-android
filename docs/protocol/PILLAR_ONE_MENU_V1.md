# Pillar One: existing menu configuration extension

This extends [BLE App Contract v1](PILLAR_ONE_BLE_V1.md). Existing framing,
authorization, session/sequence, timeout and duplicate rules remain unchanged.
No UI code or images are transferred. Firmware exposes its existing page
catalog and persists order, visibility and start page.

## Capability and limits

HELLO_ACK capability bit 2 (`0x0004`) advertises menu support. Older devices
remain usable for navigation; Android disables menu configuration for them.
Schema is exactly 1. Catalog: 1..32 pages with stable, nonzero, unique uint8
IDs. Names: 1..64 UTF-8 bytes, no controls U+0000..001F/U+007F..009F or
whitespace-only labels. Negotiated payload maximum must cover all responses
and writes; current firmware uses 192 bytes.

All multibyte integers are little-endian. All responses echo request
session/sequence. Reject invalid schema with UNSUPPORTED_VERSION, invalid
content with INVALID_PAYLOAD; no silent truncation or partial application.

## Messages

| Type | Value | Direction | Payload |
| --- | --- | --- | --- |
| GET_MENU | 0x20 | App -> display | Page index uint8 |
| MENU_STATE | 0x21 | Display -> app | One ordered catalog entry below |
| SET_MENU | 0x22 | App -> display | Complete configuration below |

Successful GET_MENU returns MENU_STATE, not ACK OK. Failed GET_MENU returns
ACK `[0x20, status]`. SET_MENU returns ACK `[0x22, status]`.
Additional statuses, valid only for menu commands: CONFLICT=8,
STORAGE_FAILED=9. Other statuses retain the base contract's meaning.

MENU_STATE payload (exactly 11 + name length bytes):

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Schema = 1 |
| 1 | 4 | Nonzero configuration revision |
| 5 | 1 | Start page ID |
| 6 | 1 | Page count |
| 7 | 1 | Requested index, 0..count-1 |
| 8 | 1 | Page ID |
| 9 | 1 | Page flags: bit 0 visible, bit 1 eligible as start page; other bits zero |
| 10 | 1 | Name byte length |
| 11 | variable | UTF-8 name, no terminator |

Index zero starts Android's read. Android requests all entries sequentially,
checks consistent revision/count/start page, unique IDs and eligible visible start
page, and publishes only a complete valid catalog. A changed/incomplete read
is an explicit failure, never a partial success. It may be retried manually.

SET_MENU payload (exactly 7 + 2 * count bytes, at most 71):

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Schema = 1 |
| 1 | 4 | Expected current revision |
| 5 | 1 | Start page ID |
| 6 | 1 | Page count |
| 7 | repeated 2 | Page ID, visibility (0/1), in requested order |

List ALL current catalog IDs exactly once, including hidden pages. Unknown,
duplicate or missing IDs are invalid. The start page must exist and be
visible and eligible according to the firmware catalog; otherwise reject with
INVALID_PAYLOAD. Navigation and media pages (IDs 6 and 7 in the current
firmware) are not eligible because they require a phone. The app disables
their start-page action based on the advertised flag, not hard-coded IDs.
SET visibility bytes remain boolean 0/1, not MENU_STATE flags.
At least one page remains visible. A stale expected revision is
CONFLICT. The firmware must retain independent access to device management
even if normal menu pages are hidden.

## Persistence and confirmation

Validate before writing. Store the complete configuration and its revision
atomically, separately from phone pairing. Failed validation/persistence must
leave the previous runtime configuration intact. Changed configuration
advances revision (never zero); a no-op may retain it. Duplicate SET requests
replay their cached response without writing flash again. GET response replay
must preserve the original MENU_STATE entry.

ACK OK for SET means durably committed and accepted into the runtime menu,
not merely received over BLE. Android then reads the full menu back and
compares order, visibility and start page before showing "saved". This is
not proof of rendered pixels. On disconnect/timeout, the outcome may be
unknown: keep the local draft, do not resend automatically, and explicitly
read the device to reconcile.

Load stored settings at boot and open the selected start page without a
phone. Invalid/outdated storage must be logged and handled explicitly with
a usable built-in menu fallback. Preserve existing pages and non-menu
functionality. Firmware mutations must run outside BLE callbacks with the
existing UI synchronization.

## Shared fixtures

Revision `0x12345678`, start ID 1, count 2, first page ID 1, visible and eligible,
name `Maps`: MENU_STATE payload:

```text
01 78 56 34 12 01 02 00 01 03 04 4d 61 70 73
```

SET_MENU, same revision/start/count, order ID 2 hidden then ID 1 visible:

```text
01 78 56 34 12 01 02 02 00 01 01
```

SET success ACK: `22 00`; stale revision: `22 08`; storage failure: `22 09`.

## Hardware acceptance

Read the actual firmware catalog, reorder/hide pages and change start page.
Save, verify readback, disconnect phone and restart display: order/visibility
and start page remain. Verify device management remains reachable, interrupted
transfer keeps old configuration, stale revision is rejected, and navigation,
OBD/HID/AMS behavior remains intact. Firmware is not flashed by Android.
