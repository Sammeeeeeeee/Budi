# Budi

Budi adds your Galaxy Buds' noise control mode to the status bar, beside Wi‑Fi and battery. It shows Off, Ambient sound, Adaptive or Active noise canceling, using Samsung's own One UI glyphs. Budi never connects to the buds, so everything Samsung's buds features already do keeps working.

## How it works

- **Reading the mode.** Samsung's buds plugin (Galaxy Buds3 Pro Manager) logs every frame it exchanges with the buds (`RECV++ : FD … DD` and `SENT-- : FD … DD`). Budi's follower reads those lines from logd, filtered to the plugin's process. It decodes them with the protocol from [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient):
  - the extended status the buds send on connect;
  - the update after a change made on the buds;
  - a mode set from the phone, and the buds' acknowledgement of it.
- **The icon.** Set with `IStatusBarService.setIcon`, using the glyphs from Samsung's plugin on the phone. Budi's own lookalikes stand in if those are missing. The call needs `android.permission.STATUS_BAR`, which the shell user holds, so the follower runs as a [Shizuku](https://shizuku.rikka.app) user service.
- **Background cost.** While the buds are connected there's one small process, blocked on a socket. It wakes when the plugin logs, and at most every 30 s to check the plugin hasn't restarted. There's no wake lock and no notification. Budi's app runs for about a second when the buds connect or disconnect.

## Why not talk to the buds directly

The buds accept one control link at a time. Opening one knocks Samsung's plugin off: see [Headphone Bridge's Android spike](https://github.com/matthewbpt/headphone-bridge/blob/main/docs/spikes/android-rfcomm.md). Apps such as [BudBuddy](https://github.com/BenEgeDeniz/BudBuddy) replace Samsung's app for that reason. Budi only reads what Samsung's plugin already logs.

## Setup

1. Install the APK from [Releases](../../releases) and open Budi.
2. Allow **Nearby devices**, **Shizuku** and **Battery**.

You need a Samsung phone with Samsung's buds plugin, which installs when you pair the buds, and Shizuku running. Without root, Shizuku has to be started again after each reboot.

If the icon is wrong or missing, tap **Report a problem › Copy** in Budi and share what it copies. It lists what the follower has seen lately.

## Limits

- Android puts icons set by apps at the far left of the system icons, and hides the leftmost first when the bar is full. AOSP shows at most 7 there; with many icons on (NFC, alarm, Bluetooth and so on), Budi's shows only in the expanded panel. Launchers that hide the status bar hide it too.
- Budi relies on Samsung's plugin logging the frames it exchanges. A plugin update could change that.
- It was built for Galaxy Buds3 Pro on One UI 9.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17+ and Android SDK platform 37. Pushing a `v*` tag, or running the Release workflow by hand, builds the APK and publishes it as a GitHub release.

The workflow signs with the repository's `SIGNING_KEY` secret (a base64 keystore holding a key named `budi`) and `SIGNING_PASSWORD`. Without them it uses a throwaway debug key, so each release must be installed fresh, after uninstalling the last.
