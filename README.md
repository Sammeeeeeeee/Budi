# Budi

Shows your Galaxy Buds' noise control mode as a status bar icon beside Wi‑Fi and battery, using glyphs styled after One UI's own: Off, Ambient sound, Adaptive and Active noise canceling. Built for Galaxy Buds3 Pro on One UI 9.

## How it works

- **Reading the buds.** Budi opens the buds' Bluetooth serial (RFCOMM) channel and listens. The buds push their state on connect and whenever the mode changes. Budi only reads and never writes to the buds. The protocol comes from [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient).
- **The icon.** The icon is placed with `IStatusBarService.setIcon`. This needs `android.permission.STATUS_BAR`, which the shell user holds, so the call goes through [Shizuku](https://shizuku.rikka.app). Without Shizuku, the mode shows as a notification icon instead.
- **Background cost.** A manifest receiver starts Budi when the buds connect and stops it when they disconnect. While they're connected, a foreground service holds one socket. When idle, that service is a single thread blocked on `read()`, with no polling and no wake locks.

## Samsung's manager

Android allows one connection per RFCOMM channel per device. Samsung's **Galaxy Buds3 Pro Manager** (`com.samsung.accessory.paranmgr`) holds that channel while it's enabled. So Budi can only read the buds while the manager is off. While it's off, One UI's buds settings, quick panel controls and firmware updates won't work. If the manager is on, Budi detects it and doesn't try to take the channel.

To try Budi, turn the manager off from a Shizuku shell or adb, and turn it back on the same way:

```
pm disable-user --user 0 com.samsung.accessory.paranmgr
pm enable com.samsung.accessory.paranmgr
```

**Looking for a way around it.** While the manager is on, Budi offers a **Diagnostic report**, so the manager can stay on in the long run:

1. Switch noise modes on your buds a few times.
2. Tap **Run**. Budi uses Shizuku to collect what One UI exposes:
   - Bluetooth metadata changes
   - settings keys
   - the manager's components and broadcasts
   - logs
3. Budi saves the report as a text file in Downloads, with full Bluetooth addresses replaced by placeholders. Nothing is uploaded.

## Setup

1. Install the APK and open Budi.
2. Allow:
   - **Nearby devices**, to read the buds.
   - **Shizuku**, for the icon beside Wi‑Fi and battery.
   - **Notifications**, for the fallback icon.
   - **Battery**, so Budi isn't put to sleep.
3. Optional: once the status bar icon works, long-press Budi's notification and turn off **Background connection**. The other channel, **Noise control**, still shows the mode whenever Shizuku isn't running.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17+ and Android SDK platform 37. The release build is signed with the local debug key so it can be sideloaded.
