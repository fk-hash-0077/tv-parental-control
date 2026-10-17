# TV Guard

Parental controls for a Google TV / Android TV, run from a phone.

- **TV app** (`tv/`) runs in the background as Device Owner. It locks YouTube when you block it
  or when today's watch time reaches the daily limit, and unlocks it again at midnight.
- **Phone app** (`phone/`) pairs with the TV using a code shown on screen. From the phone you
  can turn YouTube on or off, set the daily limit, and see how much was watched today.
- The two talk through a free Firebase Realtime Database, so the phone works away from home Wi-Fi.

Kids use the TV's main profile. Google TV Kids profiles aren't supported, because a Device Owner
app can't manage apps inside another profile.

## How it works

| Piece | What it does |
| --- | --- |
| Device Owner | `DevicePolicyManager.setPackagesSuspended` greys out YouTube; the app also blocks its own uninstall. |
| Watch time | `UsageStatsManager` events since local midnight for the blocked packages. |
| Background | A foreground service re-checks every 30 seconds and on every change from the phone, and restarts after reboot. |
| Sync | `devices/{code}/config` is written by the phone; `devices/{code}/status` is written by the TV. |

Blocked by default: `com.google.android.youtube.tv` and `com.google.android.youtube`. The list is
`config/packages` in the database if you want to add more apps.

## 1. Firebase setup (one time, free)

1. Create a project at <https://console.firebase.google.com>.
2. Add two Android apps with package names `com.tvguard.tv` and `com.tvguard.phone`.
3. Download `google-services.json` (it covers both apps).
4. **Build > Authentication**: turn on the **Anonymous** sign-in provider.
5. **Build > Realtime Database**: create a database, then paste `database.rules.json` into its Rules tab.
6. In the GitHub repo, go to **Settings > Secrets and variables > Actions** and add a secret
   named `GOOGLE_SERVICES_JSON` with the full contents of the file.

Without this the apps still build, and the TV enforces its built-in defaults
(YouTube allowed, 60 minutes a day), but the phone can't connect.

## 2. Get the APKs

Every push to `main` builds both apps. Open the **Actions** tab, pick the latest
**Build APKs** run and download `tv-guard-apks`.

## 3. Set up the TV (one time)

You need a computer with `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools))
on the same Wi-Fi as the TV. Nothing is plugged in.

1. Factory reset the TV. During setup, choose **basic TV** / skip signing in to Google.
   Device Owner can only be set while the TV has no accounts and no extra profiles.
2. Turn on developer options: **Settings > System > About**, select **Android TV OS build**
   seven times.
3. In **Settings > System > Developer options**, turn on **Wireless debugging** (or
   **USB debugging**, which on many TVs also allows network connections on port 5555).
   Note the TV's IP address and port.
4. Run:

   ```sh
   # TVs with "Wireless debugging": use "Pair device with pairing code" for the last two arguments
   scripts/setup-tv.sh 192.168.1.50:37000 tv-debug.apk 192.168.1.50:41234 123456

   # TVs where USB debugging allows network adb
   scripts/setup-tv.sh 192.168.1.50:5555 tv-debug.apk
   ```

5. The TV shows TV Guard with three green checks and a pairing code. You can now sign in to
   your Google account on the TV again if you want.
6. Optional: turn developer options back off so kids can't use them.

## 4. Set up the phone

Install `phone-debug.apk` on your Android phone, open it, and type the code from the TV.

## Removing protection

In the phone app, use **Remove protection from TV**. The TV unlocks YouTube, gives up Device Owner,
and can then be uninstalled normally. Without the phone app (for example if Firebase isn't set up),
the only way to remove it is a factory reset.

## Known limits

- Anyone who sees the pairing code and has the phone app can control the TV. Keep the phone APK to yourself.
- Watch time is counted per minute and checked every 30 seconds, so a session can run up to about
  a minute past the limit.
- Only the apps on the list are locked. Other video apps (or a browser, if one is installed) aren't.
