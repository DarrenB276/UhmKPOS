# Firebase setup

The app already runs without this — it works fully offline and keeps everything on the phone.
Do this when you want **staff logins**, **sync between phones**, and **notices to staff**.

> This workspace already contains a Firebase configuration for project `uhmk-pos`. If that is
> your project, verify Authentication, Firestore, the admin document, and rules below; you do not
> need to create a second project.

Budget: everything below stays on Firebase's free **Spark** plan. You never enter a card.

Total time: about 15 minutes.

---

## 1. Create the project

1. Go to <https://console.firebase.google.com> and sign in with your Google account.
2. **Create a project** → name it `UhmK POS` → Continue.
3. Google Analytics: **turn it off**. You don't need it and it just adds prompts.
4. Wait for it to finish, then **Continue**.

## 2. Register the app — you need TWO entries

On the project overview, click the **Android** icon.

> ⚠️ **This is the step people get wrong.** The test build and the real build have different
> package names on purpose, so you can keep both installed at once. Firebase needs **both**
> registered or the build will fail with *"No matching client found for package name"*.

**First app:**
- Android package name: `com.uhmk.pos`
- Nickname: `UhmK POS (release)`
- Leave the SHA-1 blank → **Register app**
- **Download `google-services.json`** → **Next → Next → Continue to console**

**Second app** — back on the overview, click **Add app → Android**:
- Android package name: `com.uhmk.pos.debug`
- Nickname: `UhmK POS (test)`
- **Register app**, then **download `google-services.json` again**

The second download contains *both* apps. That is the file you want.

## 3. Drop the file into the project

Replace the placeholder:

```
<project-folder>\app\google-services.json
```

Open it in Notepad and check it lists **both** `com.uhmk.pos` and `com.uhmk.pos.debug`.
If it only has one, re-download it from the second app in step 2.

## 4. Turn on Authentication

1. Left menu → **Build → Authentication → Get started**
2. **Sign-in method** tab → **Email/Password** → enable the first toggle → **Save**
3. **Users** tab → **Add user**
   - Your email and a password you'll remember
   - **Add user**
4. Copy the **User UID** that appears in the row — you need it in the next step.

## 5. Turn on Firestore

1. Left menu → **Build → Firestore Database → Create database**
2. Location: pick **asia-southeast1 (Singapore)** — closest to the Philippines, so the app feels faster.
3. Start in **production mode** → **Create**

## 6. Make yourself the admin

Firestore decides who is an admin, and your account has no record yet. Create it by hand once:

> Creating a login in Authentication is only half of the setup. Every login also needs a document
> in `users` whose document ID is the Authentication UID. Without it, the person can authenticate
> but admin permissions, account status, notices, and reliable sync will not work correctly.

1. In Firestore, click **Start collection**
2. Collection ID: `users` → Next
3. Document ID: **paste the User UID from step 4**
4. Add these fields exactly:

| Field | Type | Value |
|---|---|---|
| `uid` | string | *the same UID* |
| `email` | string | your email |
| `displayName` | string | your name |
| `role` | string | `ADMIN` |
| `active` | boolean | `true` |
| `createdAt` | number | `0` |
| `updatedAt` | number | `0` |

5. **Save**

> Get `role` exactly right — capital `ADMIN`. Anything else and you'll sign in as staff and
> won't see Reports or Settings.

## 7. Lock down the security rules

Firestore **Rules** tab → replace everything with this → **Publish**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function signedIn() {
      return request.auth != null;
    }

    function isAdmin() {
      return signedIn()
        && exists(/databases/$(database)/documents/users/$(request.auth.uid))
        && get(/databases/$(database)/documents/users/$(request.auth.uid)).data.role == 'ADMIN';
    }

    // Staff need prices to sell. Admin catalogue writes must use the revision transaction added
    // in v2.6.1; this blocks an old installation from uploading a stale whole product record.
    match /items/{itemId} {
      allow read: if signedIn();
      allow create: if isAdmin()
        && request.resource.data.revision == 1
        && request.resource.data.serverUpdatedAt == request.time;
      allow update: if isAdmin()
        && request.resource.data.revision == resource.data.get('revision', 0) + 1
        && request.resource.data.serverUpdatedAt == request.time
        && !(resource.data.get('costKnown', false) == true
          && request.resource.data.costKnown != true);
      allow delete: if isAdmin();
    }

    // Staff record sales. An identical retry is allowed so a connection drop after upload does
    // not strand the receipt as permanently "dirty" on the phone.
    match /sales/{saleId} {
      allow read:   if isAdmin();
      allow create: if signedIn()
        && request.resource.data.cashierId == request.auth.uid;
      allow update: if isAdmin()
        || (signedIn()
          && resource.data.cashierId == request.auth.uid
          && request.resource.data.diff(resource.data).affectedKeys().hasOnly([]));
      allow delete: if isAdmin();
    }

    match /users/{uid} {
      allow read:  if signedIn();
      allow write: if isAdmin();
      // A staff member may register their own push token or deactivate their own account.
      // Without this restriction they could set their own role to ADMIN.
      allow update: if signedIn()
        && request.auth.uid == uid
        && (
          request.resource.data.diff(resource.data).affectedKeys().hasOnly(['fcmToken'])
          || (request.resource.data.diff(resource.data).affectedKeys().hasOnly(['active', 'updatedAt'])
            && request.resource.data.active == false)
        );
    }

    match /notices/{noticeId} {
      allow read: if isAdmin()
        || (signedIn()
          && (
            !resource.data.keys().hasAny(['targetUid'])
            || resource.data.targetUid == ''
            || resource.data.targetUid == request.auth.uid
          ));
      allow write: if isAdmin();
    }

    // PIN-confirmed tally edits are private owner audit records.
    match /auditLogs/{logId} {
      allow read, write: if isAdmin();
    }
  }
}
```

## 8. Rebuild

In PowerShell:

```bash
cd <project-folder>; .\rebuild.ps1
```

Install the new APK, sign in with the email and password from step 4, and Settings should now
read **"Cloud sync on"**.

> Install v2.6.1 or newer on every store phone before entering real supplier costs. These rules
> intentionally refuse catalogue writes from older builds, while sales remain safely stored on
> the phone until its app is updated.

## 9. Add staff and additional admins

In the app, sign in as an admin and open **Settings → Staff accounts → Add account**. Give the
person a name, unique email, and temporary password, then choose one role:

- **Staff** — can sell, view read-only stock, use Day Tally, and receive notices.
- **Admin** — can additionally see profit/reports, all receipts, edit inventory/categories,
  manage accounts, change store settings, and send notices.

The app creates both the Firebase Authentication login and its matching Firestore `users/{uid}`
role document. The new person installs the same release APK and signs in with those details. Keep
at least two active admin accounts once the store relies on cloud sync, so one admin can restore
access if the other loses a phone or password.

Staff see Sell, read-only Stock, Day tally, Notices, and their account/appearance settings.
Reports, all receipts, inventory editing, categories, store setup, staff management, and data
reload are admin-only. Profit figures are hidden from staff unless the admin enables them.

Sales are uploaded by every cashier and downloaded onto the admin phone, so the admin Reports and
date tally include staff receipts. Voids and returns synchronize as status records and remain in
Order history without counting toward active sales or profit. Item photos stay on the phone that
selected them; the free setup does not upload images. A launch PIN is also per user and per device;
it is not a replacement for the Firebase account password.

---

## Notices: how delivery actually works

Sending a real push notification requires a trusted server, and on Firebase that means Cloud
Functions — which is **not** on the free Spark plan.

So the app does it without a server. Each staff phone keeps a small background service running
that watches the `notices` collection and raises the notification itself. A background job also
re-checks every 15 minutes as a safety net.

In practice: near-instant while the phone is awake and on a network, and caught up within 15
minutes otherwise. Admin-deleted messages are removed from staff inboxes on the same sync path.
Good enough for "come in early tomorrow", not for something urgent to the minute.

Scheduled reminders and the daily low-stock alert are generated on the phone with Android's
background scheduler, so they still work without internet after they have been configured. Android
may delay an exact time slightly to save battery. Internet is only required for cloud-sent admin
messages and cross-device syncing.

**If you ever upgrade to Blaze**, the app is already wired for real push — token registration and
the receiving service ship in this build. You'd only need to deploy a Cloud Function that watches
`notices` and calls FCM. No app changes.

---

## Troubleshooting

**"No matching client found for package name 'com.uhmk.pos.debug'"**
You only registered one app. Go back to step 2 and add the second one.

**Sign-in says "Wrong email or password" but you're sure it's right**
Check Authentication → Sign-in method → Email/Password is actually enabled.

**You sign in but Reports are missing**
Your `users/{uid}` document is missing or `role` isn't exactly `ADMIN`. Recheck step 6, then
sign out and back in.

**"Missing or insufficient permissions" in the logs**
The rules from step 7 weren't published, or your admin document doesn't exist yet.

**Settings still says "Running on this device only"**
The new `google-services.json` didn't make it into `app/`, or you installed an APK built before
you replaced it. Run `rebuild.ps1` again and reinstall.

**Nothing syncs but there's no error**
Free Spark allows 50,000 reads and 20,000 writes a day. A small shop won't come close, but the
quota resets at midnight Pacific time if you ever do.
