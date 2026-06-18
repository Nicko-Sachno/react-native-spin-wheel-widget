# react-native-spin-wheel-widget

An animated **spin-wheel Android home-screen widget**, exposed to React Native as an installable
library. The wheel is a native `AppWidgetProvider` + `RemoteViews` that renders rotated bitmap
frames for a smooth, eased spin on tap; React Native acts as a **headless control bridge** that
configures the widget, triggers refreshes, reads results, and receives spin events.

> **Why a bridge and not a `<View>`?** A home-screen widget lives in the **launcher** process, so it
> can't be embedded inside an RN screen. The library is therefore a TurboModule that *configures and
> drives* the native widget, rather than a rendered component.

Android only. (An iOS stub is present, but the widget is Android-specific.)

## Installation

From the packaged tarball (per the assignment deliverable):

```sh
npm install ./react-native-spin-wheel-widget-0.1.0.tgz
# or: yarn add ./react-native-spin-wheel-widget-0.1.0.tgz
```

The library declares its `AppWidgetProvider` in its own manifest, so it **merges into your app
automatically** — no manifest edits needed. After installing, the **Spin Wheel** widget appears in
the launcher's widget picker. Requires the New Architecture (TurboModules), Android `minSdk 24+`.

## Usage

```ts
import {
  configure,
  setConfigJson,
  refresh,
  getLastResult,
  addSpinResultListener,
} from 'react-native-spin-wheel-widget';

// Point the widget at a remote JSON config (optionally override the asset host).
configure('https://your.cdn/wheel-config.json', '');

// …or push a full config JSON straight to the widget (fully offline). Pass '' to clear.
setConfigJson(JSON.stringify(myConfig));

// Ask any placed widget to reload its config + assets and re-render.
const placed = await refresh(); // true if a widget is on the home screen

// Read the widget's persisted state without triggering a fetch.
const { restingAngle, lastFetchTime, configUrl, segmentIndex, color, name } =
  getLastResult();

// Receive spin-completion events (only while the app is running).
const sub = addSpinResultListener(({ angle, widgetId, segmentIndex, color, name }) => {
  console.log(`widget ${widgetId} landed on ${name} (${color}) at ${angle}°`);
});
// later: sub.remove();
```

### API

| Function | Description |
|---|---|
| `configure(configUrl, assetsHost?)` | Persist the remote config URL (+ optional asset-host override) to the widget. |
| `setConfigJson(json)` | Push a full config JSON string (offline override). `''` clears it. |
| `refresh(): Promise<boolean>` | Force placed widgets to re-fetch the config (bypassing the soft TTL) + re-download/reconcile assets + re-render. Resolves `true` if a widget is placed. |
| `getLastResult(): SpinWheelResult` | `{ restingAngle, lastFetchTime, configUrl, segmentIndex, color, name }` — persisted state plus the segment currently under the pointer (the "prize"). |
| `addSpinResultListener(cb)` | Subscribe to `{ angle, widgetId, segmentIndex, color, name }` spin-completion events; returns a subscription. |

## Config schema

The widget fetches/parses a JSON config (sample in `android/src/main/assets/tapp_widget_config.json`):

```jsonc
{
  "data": [{
    "network": {
      "assets": { "host": "https://…/" }          // base URL for relative asset paths
    },
    "wheel": {
      "rotation": {
        "duration": 2000,                            // ms (keep ≤ ~10s — the spin runs in the broadcast window)
        "minimumSpins": 3,
        "maximumSpins": 5,
        "spinEasing": "easeInOutCubic"               // linear | easeIn/Out/InOutQuad | easeIn/Out/InOutCubic
      },
      "assets": {
        "bg": "bg.jpeg",
        "wheelFrame": "wheel-frame.png",
        "wheelSpin": "wheel-spin.png",
        "wheel": "wheel.png"
      }
    }
  }]
}
```

Networking uses **OkHttp**; parsing uses **kotlinx-serialization-json**; the parsed config is cached
to disk as **CBOR**; the last fetch time + resting angle persist in **SharedPreferences**.

**Field handling:** `network.assets.host` + the four `wheel.assets.*` (relative paths joined to the host),
`rotation.duration` / `minimumSpins` / `maximumSpins` / `spinEasing`, and `network.attributes.refreshInterval`
(soft TTL), `cacheExpiration` (hard TTL), `networkTimeout`, `retryAttempts` are all applied. `debugMode` is
reserved (unused).

## Updating the config (a new promotion)

The wheel is fully config-driven, so launching a new spinner promotion needs **no app release** — you
just change the config the widget reads. Two ways:

**A. Update the hosted `config.json`** (the URL passed to `configure(...)`):
1. Point `wheel.assets.*` at the new artwork and tweak `rotation` as desired. **The config is the
   source of truth for which images to load, so changing an image means changing the config** to
   reference the new Drive file ID — that's what triggers the download + prune. (Replacing a file
   *in place* under the same ID/filename is **not** re-downloaded — assets are cached by ID; bump the
   ID/filename to force it.)
2. The widget applies it on its next refresh: **automatically within `refreshInterval`** (soft TTL), or
   **immediately** when the app calls `refresh()` — which **forces a re-fetch, bypassing the TTL** (and
   falls back to cache/bundled if offline). New asset IDs are downloaded and old ones pruned (the cache
   reconciles to the new config); stale-while-revalidate means no blank frame during the swap.

**B. Push a promotion straight from the app** (no hosting needed):
```ts
setConfigJson(JSON.stringify(newPromotionConfig)); // offline override, highest priority
await refresh();                                   // apply it now
// setConfigJson('') clears the override → back to the hosted/bundled config
```

### Uploading a new asset to Google Drive

Assets are addressed by **Drive file ID**, served via `https://lh3.googleusercontent.com/d/<FILE_ID>`
(the config's `host` is `https://lh3.googleusercontent.com/d/` and each `wheel.assets.*` value is the ID).
To add or replace an image:

1. **Upload** the image to Google Drive.
2. **Make it link-public:** right-click → *Share* → *General access* → **Anyone with the link** (Viewer).
   lh3 only serves files that are publicly shared.
3. **Grab the file ID** from the share link —
   `https://drive.google.com/file/d/`**`<FILE_ID>`**`/view?usp=sharing` — copy the `<FILE_ID>` segment.
4. **Put the ID in the config** as the relevant `wheel.assets.*` value (e.g. `"wheel": "<FILE_ID>"`), then
   publish the config (commit/push your hosted `config.json`).
5. **Apply it:** in the app call `configure(configUrl)` then `refresh()` — the widget fetches the new
   config, downloads the new image, and prunes the old one.

Notes:
- A new image must be a **new file (new ID)** referenced by the config — that's what triggers the
  download + prune. Overwriting a Drive file *in place* keeps the same ID, so it won't be re-fetched
  (assets are cached by ID); upload a new file and point the config at its ID instead.
- The wheel/frame/spin PNGs keep their transparency through lh3; the background may be served as JPEG
  (fine — it's opaque). `BitmapFactory` decodes by content, so the file name/extension doesn't matter.
- Sanity-check a link in a browser: `https://lh3.googleusercontent.com/d/<FILE_ID>` should show the image.

## Demo app

The `example/` app is a runnable control panel for the widget:

```sh
corepack yarn install
corepack yarn example android   # build + run on a device/emulator
```

Then long-press the home screen → **Widgets** → **Spin Wheel** → place it, and **tap the center to spin**.

> If `yarn example android` can't find the React Native CLI, run it manually: `npx react-native start`
> (Metro) in one terminal, then in another: `adb reverse tcp:8081 tcp:8081` and
> `cd example/android && ./gradlew :app:installDebug && adb shell am start -n spinwheelwidget.example/.MainActivity`.

**What the buttons do:**

- **Use bundled config** — clears any overrides (`setConfigJson('')` + `configure('', '')`); the widget
  runs fully offline from the bundled config + drawables.
- **Configure remote URL** — points the widget at the hosted `config.json` (`configure(url)`). It only
  writes the URL to prefs; the actual fetch happens on the next **Refresh**.
- **Refresh widget** — `refresh()`: forces placed widgets to **re-fetch the config (bypassing the TTL)**,
  re-download/reconcile assets, and re-render. Resolves whether a widget is currently placed.
- **Read last result** — `getLastResult()`: reads the persisted state **without** a network fetch.

It also shows three live panels: **Persisted state** (`configUrl`, `restingAngle`, `lastFetchTime`, and the
landed color + swatch), **Last spin event**, and an **Event log** that records every RN callback —
`onSpinResult` arrivals plus the result of each bridge call — so you can watch the bridge working.

## How the spin works

A home-screen widget renders in the **launcher** process, so we can't run a normal animation there.
Instead the widget does a **flip-book**: in our process it draws the wheel at many angles and pushes each
frame to the launcher as a bitmap (~60/sec), which reads as a smooth spin.

1. **Plan the spin** (`planSpin`): pick a random number of full turns (`minimumSpins..maximumSpins`) plus
   a random landing angle → `travel` (total degrees to rotate), with the config's `duration`/`spinEasing`.
2. **Frame loop** (`runSpinFrames`) — each frame:
   - `t = elapsed / duration` ∈ [0,1], driven by the **wall clock** (not a frame counter);
   - `angle = start + ease(spinEasing, t) · travel` — eased, so it accelerates then glides to a stop;
   - rotate the wheel to `angle` and push **only** the wheel layer via `partiallyUpdateAppWidget`;
   - sleep the remainder of the ~16 ms (60 fps) budget, then repeat until `t == 1`.
3. **Persist + report**: store the resting angle in `SharedPreferences` and broadcast the landed segment.

Two details make it smooth:
- **Wall-clock timing** — a late/dropped frame makes the next angle *jump forward*, so the spin always
  finishes on time at a constant perceived speed instead of dragging under jank.
- **Double buffering** — two reused bitmaps (swapped with `slot xor 1`); rendering into the idle one means
  zero per-frame allocation, so there's no GC stutter mid-spin.

The "magic numbers": `1_000_000` converts `nanoTime()` ns → ms; `16` ≈ 1000 ms / 60 fps (the frame
budget); the wheel bitmap is capped to **320²** because each frame crosses processes over Binder (~1 MB
limit) and 320²×4 ≈ 410 KB stays safely under.

## Measuring spin smoothness

The widget logs per-spin frame stats (toggle `MEASURE` in `WheelSpinWidget.kt`):

```sh
adb logcat -s WheelSpin
# frames=… fps=… (target=63) interval avg=…ms jitter=±…ms dropped=N work avg=…ms …
```

`jitter` and `dropped` are the smoothness signals — both should stay low.

## License

MIT
