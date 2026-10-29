# Budi

Shows your Galaxy Buds' noise control mode as a status bar icon beside Wi‑Fi and battery, using glyphs styled after One UI's own: Off, Ambient sound, Adaptive and Active noise canceling. Built for Galaxy Buds3 Pro on One UI 9.

## How it works

- **Reading the buds.** Budi opens the buds' Bluetooth serial (RFCOMM) channel and listens. The buds push their state on connect and whenever the mode changes. Budi only reads and never writes to the buds. The protocol comes from [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient).
- **The icon.** The icon is placed with `IStatusBarService.setIcon`. This needs `android.permission.STATUS_BAR`, which the shell user holds, so the call goes through [Shizuku](https://shizuku.rikka.app). Without Shizuku, the mode shows as a notification icon instead.
- **Background cost.** A manifest receiver starts Budi when the buds connect and stops it when they disconnect. While they're connected, a foreground service holds one socket. When idle, that service is a single thread blocked on `read()`, with no polling and no wake locks.

## Samsung's manager

Samsung's plugin holds the same RFCOMM channel while it's enabled. On Buds3 Pro the plugin is **Galaxy Buds3 Pro Manager** (`com.samsung.accessory.paranmgr`), and on Buds4 and newer it's **Galaxy Buds** (`com.samsung.accessory.budsunitemgr`). Only one app's link can be open at a time. [Headphone Bridge's Android spike](https://github.com/matthewbpt/headphone-bridge/blob/main/docs/spikes/android-rfcomm.md) measured what happens on a real phone: each new connection attempt knocks off whoever holds the link, and Samsung's plugin retries 3 times about 3 s apart, then gives up. The other Android Buds apps on GitHub ([BudBuddy](https://github.com/BenEgeDeniz/BudBuddy), GalaxyBudsClient's Android port) replace Samsung's app rather than run alongside it.

Budi therefore doesn't connect while one of these plugins is enabled. To use Budi's direct link anyway, turn the plugin off from a Shizuku shell or adb, and turn it back on the same way. While it's off, One UI's buds settings, quick panel controls and firmware updates don't work.

```
pm disable-user --user 0 com.samsung.accessory.paranmgr
pm enable com.samsung.accessory.paranmgr
```

**Looking for a way around it.** While the plugin is on, Budi offers a **Diagnostic report**. Tap Run, then switch noise modes a few times while it records for about half a minute. It produces:

- a text report from the shell user, covering the Bluetooth metadata change log, settings keys, the plugin's components, broadcasts and logs;
- a recording of Samsung's buds widget, hosted off-screen, to see whether it mirrors the mode, which Budi could follow without the link;
- contact sheets of Samsung's own icons from the phone, so Budi can use the exact One UI glyphs.

Everything is saved to Downloads, with full Bluetooth addresses replaced by placeholders. Nothing is uploaded.

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
