# UhmK POS

Current build: **2.6.0**

Version 2.6 adds an in-app updater backed by GitHub Releases. Settings can check the latest
published release, show its notes, download the signed APK, and open Android's installer.

Version 2.5 adds restart-safe held tickets, configurable payment methods (Cash, GCash,
QRPh, BPI, GoTyme and owner-added choices), account profile pictures, inactivity and
screen-off locking, photo-background product cards, account-targeted admin notices,
and admin-only setup alerts for missing costs and missing passcodes. Old-date Day Tally
entry/editing requires the current admin PIN and records before/after audit details.

A point-of-sale Android app for the UhmK store, built around one question:
**at the end of the day, how much can I actually take out?**

Your spreadsheet already answered that — `RUNNING PRICE STUDENT − SRP = PROFIT PER ITEM`. The SRP
half is capital that has to go back into restocking, so it is never counted as earnings. Every
profit figure in the app is computed that way, and SRP is always labelled *Capital*, never income.

The public project ships with a small made-up catalogue so it can be built and demonstrated safely.
The store's real catalogue, supplier costs, Firebase configuration, signing key, screenshots and
database files are intentionally excluded from version control.

---

## Getting started

The app works immediately, with no setup and no internet. Install `app-debug.apk`, tap
**Continue on this device**, and start selling.

Connect Firebase later — see **[FIREBASE_SETUP.md](FIREBASE_SETUP.md)** — to add staff logins,
sync between phones, and notices to staff.

| File | Use it for |
|---|---|
| `dist/UhmKPOS-test.apk` | Testing. Installs alongside the real app, shows more diagnostics. |
| `dist/UhmKPOS-release.apk` | Daily use. Smaller, faster, signed. |

Both can be installed at once — they are separate apps on the phone.

### Installing on a phone

1. Copy the APK to the phone (USB, Google Drive, or email it to yourself).
2. Open it. Android will warn about installing from an unknown source — allow it for your file
   manager or browser. This is normal for an app that is not on the Play Store.
3. Install, open, done.

---

## What's in it

**Sales** — Tap items to build a sale. Regular pricing is the default; a dropdown next to Category
switches every item and cart line to Regular or Student in one tap. Create renamed product pages
containing only your quick-access products, and long-press a product to pin or unpin it. Choose
Dine-in or Takeout, apply a discount, choose a payment method from the owner-managed list,
enter cash when needed, and add an order note. Orders can also be held as named tickets and
reopened later to add products, change payment details, or complete the sale.
The order type, payment, cash, discount, note, totals and checkout controls remain fixed while a
large cart scrolls above them.

**Stock** — Every item with its cost, both prices, margin and stock level. Add and edit items,
attach a photo from the gallery, set whether stock is tracked, and enter stock/box quantities.
Manage and rename item categories, quickly filter products needing a cost, and see low-stock
warnings. Services such as Cooking fee never run out.

**Reports** — Today, Yesterday, This week, This month, Last month, Last 7/30 days, or a custom
calendar range with separate start and end dates. Take-home profit is the headline; revenue,
restocking capital, discounts, receipt count and units sold sit beneath it. Switch among Line,
Bar, and category Pie charts, group chart points by Day, Week, Month, or Year, then switch between
image-backed product, category, employee, payment type, Dine-in/Takeout, and Student/Regular
breakdowns, with best seller and top earner.

**Day tally (Calculator)** — Scroll image-backed product cards and press `+` for how many Student
or Regular units went out. Products without a selected photo use a clear initials tile. The fixed
bar instantly shows units, products, total sales, capital and take-home. Pick one date to combine
that day's receipts, or select a start/end date to total the whole range. Admins can save a manual
tally for today or an older date. Reopening a saved tally requires the user's PIN and records a
before/after audit entry with the admin name, date and time.

**Order history and receipts** — Open from Reports. Browse Completed, Voided, and Returned orders
by preset or custom calendar range. Open any order to inspect or share its print-friendly
32-column receipt. An admin can void a mistake or return an order with a reason; either action
removes the sale from totals, restores tracked stock, and keeps the marked receipt in the audit
history.

**Notices** — An admin can push a message to every staff phone. The system notification hides the
message body until it is tapped. Messages are synchronized and only an admin/owner can delete them.
An optional alert also notifies signed-in admins of each synchronized sale with receipt number,
total, cashier, date and time.

**Settings** — Store name, currency, price-tier names, theme, accent colour, low-stock threshold,
whether staff can see profit, staff accounts, and reloading the built-in price list. Admins can add
multiple one-time or everyday reminders with independent titles, notes and times, and configure the
daily low-stock alert (enabled by default for 10:30 AM–9:30 PM). Each user can protect launch with a
4–6 digit PIN, choose instant unlock after the correct PIN, send a password-reset email, or delete
their account. The top-right profile menu also provides Account settings and Lock now. The app can
check this repository's latest GitHub Release, display its notes, download the signed APK, and open
Android's installer without a separate update server.

**CSV export** — From Reports: profit by item, sales by category, active sale lines, complete order
and receipt history (including voids/returns), or full inventory. Files open in Excel or Google Sheets.

---

## Two things worth knowing

**Prices are frozen onto each sale.** When a sale is saved it stores its own copy of the price and
the cost. Raise a price next month and last month's reported profit does not move. This is the
difference between a report you can trust and one that quietly rewrites history.

**Money is stored as whole centavos, never as decimals.** Floating-point pesos drift by fractions
that eventually stop your daily total agreeing with the sum of its lines. Integers make every
total exact.

Discounts follow the same discipline: a sale-level discount is split across the lines in
proportion to their value, with the leftover centavo given to the largest line, so the per-item
report always adds up to the sale total exactly.

---

## Costs and true zero-cost services

The import provides both Regular and Student prices but not every supply cost. A blank
cost is reported as **unknown**, never guessed as profit. Enter it from Stock to complete the
take-home figure. A real zero-cost service is different: enable **Zero-cost service** in the item
editor. Cooking fee ships this way, so all ₱20 Student / ₱28 Regular is correctly take-home.

---

## For whoever maintains this

**Picking up unfinished work?** Start with **[NEXT_STEPS.md](NEXT_STEPS.md)** — it lists what is
still to build (multi-device login, shifts, expenses, barcode, printing), the decisions already
settled on each, and the constraints that are easy to trip over.

Native Android: **Kotlin + Jetpack Compose**, Material 3.

Room is the source of truth and Firestore is a sync layer on top — never the other way round. A
sale is written locally and returns immediately; syncing happens afterwards in the background.
That is why the till keeps working when the shop wifi drops, and why the app was usable before
Firebase existed at all.

```
app/src/main/java/com/uhmk/pos/
├── core/
│   ├── db/        Room entities, DAOs, the spreadsheet seeder
│   ├── repo/      Repositories — the only things that touch the database
│   ├── sync/      FirebaseGate (detects the placeholder config), SyncManager, SyncWorker
│   ├── notify/    Foreground listener service, notifications, FCM receiver
│   ├── export/    CSV building and sharing
│   ├── money/     Centavo handling and ₱ formatting
│   └── ui/        Theme, shared components, navigation, ViewModel wiring
└── feature/       sell · inventory/categories · tally · reports · sales/receipts · notices · auth · settings
```

No DI framework. `AppContainer` builds everything by hand, `appViewModelFactory` wires the
ViewModels. For an app this size that is less machinery and one less annotation processor to
break the build.

### Building

```bash
cd <project-folder>; .\rebuild.ps1
```

`-Install` pushes the test build to a connected device; `-ReleaseOnly` skips the test build.

Toolchain: Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.11, compileSdk 36, minSdk 24
(Android 7.0 and up).

> Kotlin has to stay on the 2.3.x line: the 2026 AndroidX artifacts ship Kotlin 2.4 metadata that
> only a 2.3+ compiler can read, and KSP has no 2.4 release yet. Moving Kotlin up will break the
> build until KSP catches up.

### Changing the starting catalogue

Copy `app/src/main/assets/seed_items.example.json` to the ignored
`app/src/main/assets/seed_items.json`, then replace the made-up rows with the private catalogue.
It loads on first launch, and **Settings → Reload built-in price list** re-applies it while keeping
photos, stock counts and regular prices.

---

## Keep these safe

`uhmkpos-release.jks` and `keystore.properties` sign the release build. Back them up somewhere
private.

If you lose them you cannot ship an update that Android accepts as the same app — everyone would
have to uninstall and reinstall, losing anything not yet synced. Neither file is in version
control, by design.
