# UhmK POS — remaining work

Handoff notes for whoever picks this up next, human or AI. Everything here was scoped and agreed
with the store owner; the decisions in each section are settled, not open questions.

Read **[README.md](README.md)** first for the architecture. This file covers only what is *not*
built yet, plus the constraints that will bite you if you don't know them.

---

## Ground rules that are easy to get wrong

**Kotlin must stay on 2.3.x.** The 2026 AndroidX/Compose artifacts ship Kotlin 2.4 metadata, which
only a 2.3+ compiler reads, and KSP (needed by Room) has no 2.4 line yet — it tops out at 2.3.11
under its own independent versioning. Pinned: Kotlin 2.3.21, KSP 2.3.11, AGP 8.13.2, Gradle 8.13.
Bumping Kotlin breaks the build until KSP catches up.

**Room is the source of truth; Firestore is a sync layer.** A sale is written locally and returns
immediately. Never put a network call on the path of recording a sale. Rows carry `updatedAt` +
`dirty`; the sync pushes dirty rows and resolves last-write-wins.

**Database is at version 9.** Add a `Migration(9, 10)` in `core/db/AppDatabase.kt` and register it
in `addMigrations(...)`. Follow the existing style — every migration there has a comment saying
*why*. Never destructive-migrate: sales are money records.

**Money is `Long` centavos everywhere.** Never a Double. `core/money/Money.kt` formats,
`core/money/Allocation.kt` splits discounts so parts always sum to the whole.

**A cost of `0` means "not known yet", not free.** 43 of the 85 items still have no cost. Anything
computing profit must exclude unknown-cost lines and report their revenue separately — see
`ItemEntity.costKnown`, `SaleLineEntity.costKnown`, `RangeTotals.unknownNet`. Never show a margin
for an item with no cost; the UI says "cost?" instead.

**No DI framework.** `core/AppContainer.kt` builds everything by hand; `core/ui/AppViewModels.kt`
wires ViewModels. Add new repositories/ViewModels in both.

**Firebase is on the free Spark plan.** No Cloud Functions. That is why staff notices work by each
device holding its own Firestore listener (`core/notify/NoticeListenerService.kt`) rather than real
push. Do not design anything that needs a server.

**Adaptive layout:** `core/ui/Adaptive.kt` — `rememberWindowSize()`, `supportsTwoPane`,
`productTileMinWidth()`. Phones are COMPACT; docked panels and one-row headers are gated on
`supportsTwoPane`.

---

## 1. Multi-device login and device registry

The largest remaining piece, and the foundation for items 2 and 3. Build this first.

### Decisions (settled)

- Second device is **blocked**, not kicked. The device already taking orders is never interrupted.
  The blocked device shows which device holds the session.
- Staff multi-device is approved **two ways**: admin types their passcode on the staff device, or
  approves remotely from their own phone. Build both.
- **Max 3 devices** for a staff account.
- Display names: admin `UserAdmin1 - (DeviceName1)`; staff `Staff1 - (John Dizon | DeviceName)`
  where "John Dizon" is the operator name entered on the second device.

### Data model

New entity `DeviceSessionEntity` (table `device_sessions`), synced:

```
id            String  PK   // stable per install, e.g. Settings.Secure.ANDROID_ID hashed
uid           String       // account it belongs to
deviceName    String       // Build.MODEL, editable by the user
operatorName  String       // "" for admin; the prompted name for a shared staff device
role          String
lastSeenAt    Long         // heartbeat
signedInAt    Long
approved      Boolean      // staff second/third device needs approval
revokedAt     Long?
dirty         Boolean
```

Firestore path: `users/{uid}/devices/{deviceId}`. Security rules in
**[FIREBASE_SETUP.md](FIREBASE_SETUP.md)** need a matching `match /users/{uid}/devices/{id}` block —
a user may write their own device doc; only an admin may write `approved`.

### Presence

Heartbeat `lastSeenAt` every ~60s while the app is foregrounded (reuse the existing
`SyncWorker` cadence or a light `LaunchedEffect` in `PosApp`). Treat a device as **online** if
`lastSeenAt` is within ~3 minutes, otherwise "last seen 2h ago". Do not use Firebase Realtime
Database presence — the project is Firestore-only.

### Where to hook enforcement

`feature/auth/AuthService.kt`, inside `signIn(...)` after Firebase auth succeeds but **before**
`sessionStore.signIn(session)`:

1. Read `users/{uid}/devices` where `revokedAt == null`.
2. Filter to devices seen recently (online) and not this device.
3. If any exist and multi-device is off for that account → return
   `Result.failure` with a message naming the holding device. Sign the Firebase user back out.
4. If the account is staff with multi-device on and there are already 3 approved devices → refuse.
5. Otherwise register/refresh this device's doc and continue.

`signOut()` must clear `revokedAt`/delete this device's doc, or the account locks itself out.
There is already a "Log out" item in the account menu (`core/ui/PosApp.kt`) — wire it there.

### Approval flow

- Staff toggles "Use on more than one device" in Account settings → writes a request doc.
- **On-device path:** dialog asks for the admin passcode. Verify against `core/prefs/PinStore.kt`
  (already exists, used by the lock screen). On success mark `approved = true`.
- **Remote path:** the request raises a notice to the admin (`NoticeRepository.postAlert` posts a
  local alert; for a *remote* request it must be a real synced notice so it reaches the admin's
  phone — use `compose(...)` with `targetUid` set to the admin). Admin taps Approve in the notice.
- Once approved, the second device prompts for the **operator name** before it can sell, and stores
  it as `operatorName`.

### UI

- Account settings: list devices with online dot, name, operator, last seen, and Revoke.
- Notices: allow addressing a notice to one device of a shared staff account. `NoticeEntity`
  already has `targetUid`/`targetName`; add an optional `targetDeviceId`.

---

## 2. Shift management and cash drawer

Highest-value business feature after the above. Staff handle cash and there is currently no control.

- `ShiftEntity`: `id, openedAt, openedBy, openingFloatCentavos, closedAt?, closedBy?,
  countedCentavos?, expectedCentavos?, note, dirty`.
- Expected = opening float + cash sales during the shift − cash refunds. Only **cash** payments
  count; `SaleEntity.paymentMethod` already records the method.
- Variance = counted − expected. Show it prominently; that number is the whole point.
- Stamp `shiftId` onto `SaleEntity` so reports can group by shift (needs a migration).
- Block selling when no shift is open, or warn — owner's preference, ask.
- Report per shift: sales, cash expected, counted, variance, who opened/closed.

## 3. Expense logging

Without this "take-home" overstates what is actually kept — gas, electricity and supplies come out
of that cash.

- `ExpenseEntity`: `id, occurredAt, category, note, amountCentavos, recordedBy, dirty`.
- Categories: free text with suggestions (Gas, Electricity, Supplies, Rent, Transport, Other).
- Reports: subtract expenses from take-home for the selected range and show it as its own line —
  do **not** silently fold it into the existing profit figure. Add "Net after expenses".
- Include in the CSV exports (`core/export/CsvExporter.kt`).

## 4. Barcode scanning

Hardware not available for testing yet; build it working and leave it.

- SKUs are already stored on `ItemEntity.sku`, and `ItemDao.getBySku` exists.
- Use ML Kit barcode scanning (`com.google.mlkit:barcode-scanning`) with CameraX, or
  `play-services-code-scanner` for a zero-UI scanner (smaller, no camera permission handling).
- Two entry points: scan on the Sell screen adds straight to the cart; scan in the item editor
  fills the SKU field.
- Handle "no match" clearly — offer to create an item with that SKU.

## 5. Bluetooth thermal receipt printing

Also untestable without hardware. The hard part is already done.

- `core/export/ReceiptFormatter.kt` already renders a correct 32-column receipt. Printing is
  ESC/POS bytes over an RFCOMM socket to the paired printer — mostly `ESC @` init, the text, then
  a feed and cut.
- Needs `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` runtime permissions on API 31+.
- Printer picker in Settings storing the MAC address; Print action on `ReceiptScreen`.
- Keep it failure-tolerant: printing must never block or fail a sale that is already recorded.

---

## Verification gaps in the current build

Worth confirming before building on top:

- **Docked cart panel** compiles and is wired but was never seen running. Set Settings →
  Appearance → *Current sale panel* to Docked right on a tablet and check it.
- **Photo background cards** likewise — needs an item that actually has a photo to eyeball.
- **`sales` collection in Firestore** has never been exercised. Ring up one sale while signed in
  to a cloud account and confirm the collection appears. The push code is in place and correct.
- **Receipt device prefix** (`A-0007`) only shows once a device code is set in Settings → Store.

## Build and test

```bash
cd <project-folder>; .\rebuild.ps1
```

`-Install` pushes the test build to a connected device; `-ReleaseOnly` skips the debug build.

Test tablet layouts on the **Pixel_Tablet** AVD, not by rotating the phone AVD — the owner asked
for this specifically, and a rotated phone has ~411dp of height so it proves nothing about a real
tablet.

## Owner-facing note

Settings → Data → **Clear all sales** wipes every sale (completed, voided and returned) locally and
in Firestore, for a clean go-live. Products, prices and costs survive; stock counts are left alone
deliberately, since they have been edited by hand since the test sales ran.
