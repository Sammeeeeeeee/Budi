# Budi

Budi adds your Galaxy Buds' noise control mode to the status bar: Off, Ambient sound, Adaptive or Active noise canceling, drawn like Samsung's own One UI glyphs. Budi never connects to the buds, so everything Samsung's buds features already do keeps working.

## How it works

- **Reading the mode.** Samsung's buds plugin (Galaxy Buds3 Pro Manager) logs every frame it exchanges with the buds (`RECV++ : FD … DD` and `SENT-- : FD … DD`). Budi's follower reads those lines from logd, filtered to the plugin's process. It decodes them with the protocol from [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient):
  - the extended status the buds send on connect;
  - the update after a change made on the buds;
  - a mode set from the phone, and the buds' acknowledgement of it.
- **The icon.** One UI 7 and later show only Wi‑Fi, mobile data and battery on the right of the closed status bar; other indicators appear only in the pulled-down panel ([Sammy Fans](https://www.sammyfans.com/2025/04/16/samsung-explains-why-bluetooth-icon-is-missing-from-one-ui-7-status-bar/)). So Budi draws its own: a window the size of a status bar icon, just right of the camera, under the status bar, which shows it through. It hides whenever the status bar does. The glyphs are traced from Samsung's `ic_buds3_*` and drawn on the 24 dp status bar icon grid.
- **Its colour.** Android copies each app's status bar request (light or dark icons) into its task description. The follower watches which task is on top and turns Budi's icon dark or light to match the status bar's own icons.
- **No notification.** Android posts "Budi is displaying over other apps" while the icon shows. The follower turns that notice off for Budi alone, as you could from the shade.
- **Shizuku.** Reading other apps' logs, following the app on top and turning off a system notice all need the shell user, so the follower runs as a [Shizuku](https://shizuku.rikka.app) user service. It drives Budi's icon over a binder, and wakes Budi's app with a broadcast only the shell may send.
- **Background cost.** While the buds are connected the follower sits blocked on a socket. It wakes when the plugin logs, when you switch apps, and at most every 30 s to check the plugin hasn't restarted. Budi's app stays in memory to host the icon, idle. There's no wake lock and no polling.

## Why not talk to the buds directly

The buds accept one control link at a time. Opening one knocks Samsung's plugin off: see [Headphone Bridge's Android spike](https://github.com/matthewbpt/headphone-bridge/blob/main/docs/spikes/android-rfcomm.md). Apps such as [BudBuddy](https://github.com/BenEgeDeniz/BudBuddy) replace Samsung's app for that reason. Budi only reads what Samsung's plugin already logs.

## Setup

1. Install the APK from [Releases](../../releases) and open Budi.
2. Allow **Nearby devices**, **Display over other apps**, **Shizuku** and **Battery**.

You need a Samsung phone with Samsung's buds plugin, which installs when you pair the buds, and Shizuku running. Without root, Shizuku has to be started again after each reboot.

If the icon is wrong or missing, tap **Report a problem › Copy** in Budi and share what it copies. It lists what the follower has seen lately.

## Limits

- Budi relies on Samsung's plugin logging the frames it exchanges. A plugin update could change that.
- It was built for Galaxy Buds3 Pro on One UI 9.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17+ and Android SDK platform 37. Pushing a `v*` tag, or running the Release workflow by hand, builds the APK and publishes it as a GitHub release.

Releases are signed with `app/budi.jks`, committed so each version installs over the last. Anyone can sign an APK with that key, so install Budi only from this repository's releases.
