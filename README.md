# react-native-spin-wheel-widget

An animated **spin-wheel Android home-screen widget**, exposed to React Native as an installable
library. The wheel is a native `AppWidgetProvider` + `RemoteViews` that renders rotated bitmap
frames for a smooth, eased spin on tap; React Native acts as a **headless control bridge** that
configures the widget, triggers refreshes, reads results, and receives spin events.

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
const { restingAngle, lastFetchTime, configUrl, segmentIndex } = getLastResult();

// Receive spin-completion events (only while the app is running).
const sub = addSpinResultListener(({ angle, widgetId, segmentIndex }) => {
  console.log(`widget ${widgetId} landed on sector ${segmentIndex} at ${angle}°`);
});
// later: sub.remove();
```

### API

| Function | Description |
|---|---|
| `configure(configUrl, assetsHost?)` | Persist the remote config URL (+ optional asset-host override) to the widget. |
| `setConfigJson(json)` | Push a full config JSON string (offline override). `''` clears it. |
| `refresh(): Promise<boolean>` | Force placed widgets to re-fetch the config (bypassing the soft TTL) + re-download/reconcile assets + re-render. Resolves `true` if a widget is placed. |
| `getLastResult(): SpinWheelResult` | `{ restingAngle, lastFetchTime, configUrl, segmentIndex }` — persisted state plus the sector index currently under the pointer (geometric only; see note below). |
| `addSpinResultListener(cb)` | Subscribe to `{ angle, widgetId, segmentIndex }` spin-completion events; returns a subscription. |

> **`segmentIndex` is a geometric index, not a prize.** The wheel is modeled as 12 equal sectors with
> sector 0 centered at 12 o'clock, and `segmentIndex` is simply which sector is under the pointer for
> the resting angle. The widget has no metadata about `wheel.png` (it's opaque pixels), so it makes no
> claim about the color/label of that wedge — don't treat the index as "the prize won." Mapping sectors
> to real prizes would require the config to describe the wheel's layout (a natural next step, see
> *Design decisions*).

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
        "duration": 2000,                            // ms; clamped to [300, 8000] (spin runs in the broadcast window)
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
(soft TTL) + `cacheExpiration` (hard TTL) are all applied. `networkTimeout` and `retryAttempts` are applied
to the **asset downloads** (the initial config fetch uses fixed timeouts and no retry, since the config —
and thus its network policy — isn't known until it's parsed; it degrades via the cache → bundled fallback
chain instead). `debugMode` is reserved (unused).

## Updating the config (a new promotion)

The wheel is fully config-driven, so launching a new spinner promotion needs **no app release** — you
just change the config the widget reads. Two ways:

**A. Update the hosted `config.json`** (the URL passed to `configure(...)`):
1. Point `wheel.assets.*` at the new artwork and tweak `rotation` as desired. **The config is the
   source of truth for which images to load, so changing an image means changing the config** to
   reference the new Drive file ID — that's what triggers the download + prune. (Replacing a file
   *in place* under the same ID/filename is **not** re-downloaded — assets are cached by ID; bump the
   ID/filename to force it.)
2. The widget applies it on its next **hydrate** — when it's placed, on reboot/app update, or
   **immediately** when the app calls `refresh()` (which **forces a re-fetch, bypassing the TTL**, and
   falls back to cache/bundled if offline). Note there is **no 30-min system poll** (`updatePeriodMillis=0`,
   see *Known limitations*), so background auto-pickup isn't automatic — drive it with `refresh()` or a
   WorkManager job. When a hydrate does run, the TTL decides whether it hits the network. New asset IDs are
   downloaded and old ones pruned (the cache reconciles to the new config); stale-while-revalidate means no
   blank frame during the swap.

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

- **Use bundled config** — clears any overrides (`setConfigJson('')` + `configure('', '')`) so no remote
  *config* is fetched. Note this is **not fully offline**: the bundled config still names Drive-hosted
  assets, so when online the widget still downloads that art and reconciles its asset cache. The bundled
  drawables are the **fallback**, shown only when the asset download fails (i.e. when actually offline).
- **Configure remote URL** — points the widget at the hosted `config.json` (`configure(url)`). It only
  writes the URL to prefs; the actual fetch happens on the next **Refresh**.
- **Refresh widget** — `refresh()`: forces placed widgets to **re-fetch the config (bypassing the TTL)**,
  re-download/reconcile assets, and re-render. Resolves whether a widget is currently placed.
- **Read last result** — `getLastResult()`: reads the persisted state **without** a network fetch.

It also shows three live panels: **Persisted state** (`configUrl`, `restingAngle`, `lastFetchTime`, and the
`segmentIndex` sector currently under the pointer), **Last spin event**, and an **Event log** that records
every RN callback — `onSpinResult` arrivals plus the result of each bridge call — so you can watch the
bridge working.

## How the spin works

A home-screen widget renders in the **launcher** process, so we can't run a normal animation there.
Instead the widget does a **flip-book**: in our process it draws the wheel at many angles and pushes each
frame to the launcher as a bitmap (~60/sec), which reads as a smooth spin.

1. **Plan the spin** (`planSpin`): pick a random number of full turns (`minimumSpins..maximumSpins`) plus
   a random landing angle → `travelDegrees` (total degrees to rotate), starting from the wheel's
   `startAngle`, with the config's `duration`/`spinEasing`.
2. **Frame loop** (`runSpinFrames`) — each frame:
   - `progress = (time elapsed since the spin started) / duration` ∈ [0,1], driven by the **wall clock**
     (not a frame counter);
   - `angle = startAngle + ease(spinEasing, progress) · travelDegrees` — eased, so it accelerates then
     glides to a stop;
   - rotate the wheel to `angle` and push **only** the wheel layer via `partiallyUpdateAppWidget`;
   - sleep the remainder of the ~16 ms (60 fps) budget, then repeat until `progress == 1`.
3. **Persist + report**: store the resting angle in `SharedPreferences` and broadcast the landed segment.

Two implementation details:
- **Wall-clock timing** — the angle is derived from real elapsed time, so the spin **finishes on
  schedule** regardless of jank. This is a *duration-correctness* property, not a smoothness one: under
  a dropped frame the angle **jumps forward** to stay on schedule (a visible teleport) rather than
  slowing the whole spin down. We chose "stay on schedule" over "drag late"; neither is truly smooth
  under sustained jank.
- **One reused buffer** — a single bitmap is rewritten every frame (render → push → render again into
  the same buffer), so rendering allocates no new bitmap per frame. This is safe because
  `partiallyUpdateAppWidget` is a synchronous Binder call and `Bitmap.writeToParcel` copies the pixels
  into the parcel before it returns — so by the time the next frame overwrites the buffer, the launcher
  already has its own copy. (A double-buffered variant was prototyped and A/B'd on-device; the single
  buffer showed no tearing or frozen frames, so the second buffer was dropped as unnecessary.)

The "magic numbers": `1_000_000` converts `nanoTime()` ns → ms; `16` ≈ 1000 ms / 60 fps (the frame
budget); the wheel bitmap is capped to **320²** because each frame crosses processes over Binder (~1 MB
limit) and 320²×4 ≈ 410 KB stays safely under.

## Measuring push cadence

The widget logs per-spin frame stats (toggle `MEASURE` in `WheelSpinWidget.kt`):

```sh
adb logcat -s WheelSpin
# push cadence (producer-side, not displayed frames): frames=… fps=… (target=63) interval avg=…ms jitter=±…ms dropped=N work avg=…ms …
```

**What this does and doesn't tell you.** These numbers measure the **producer** — how fast *this*
process renders bitmaps and calls `partiallyUpdateAppWidget`. They are **not** the frames the launcher
actually draws: compositing happens out-of-process and asynchronously, and the launcher can coalesce or
drop our updates with no callback we can observe. So `jitter`/`dropped` tell you whether *our loop* is
keeping its budget — a useful diagnostic — but not whether the spin looked smooth on screen. There is no
public cross-process frame-completion signal to measure the latter; the honest test for smoothness is
your eyes on a real device.

## Design decisions & trade-offs

Why the widget is built the way it is, and the alternatives rejected:

- **The spin runs inside the AppWidgetProvider broadcast (`goAsync`), not a service.** A widget lives in
  the launcher process; `goAsync()` keeps our process alive and holds the broadcast open while the frame
  loop runs. Consequence: the window must `finish()` before the system ANR timer (~10s), so spin duration
  is clamped to ≤ 8s and the landing angle is persisted up front (a mid-spin process kill still restores
  the result, just without the animation). *Rejected:* a foreground service — correct for arbitrarily long
  animations but heavy for a sub-second flourish.
- **You can only spin when the wheel is at rest — taps are dropped, never queued.** `goAsync()` serializes
  broadcast delivery, so taps made *during* a spin are queued by the OS and replayed back-to-back when the
  spin finishes (this caused the "counts taps, runs them one-by-one" behavior). We reject them with an
  in-flight flag **plus a short post-spin cooldown** that drains that queued backlog (`WheelSpinWidget.onReceive`).
  *Rejected:* a `Mutex` (we only ever want `tryLock`, never queue/suspend a spin) and relying on an
  in-memory flag alone (it can't see the OS-queued backlog).
- **The result is a geometric sector index, not a prize.** `segmentIndex` is which of 12 equal sectors is
  under the pointer — no color/label, because the widget has no metadata about `wheel.png` (opaque pixels).
  *Rejected:* hardcoded color/name labels; they were invented and almost certainly didn't match the art.
  Mapping sectors to real prizes belongs in the config (sector layout + pointer offset) — a natural next step.
- **Offline past the hard TTL keeps the last-known-good remote config** (stale-if-error), without
  overwriting it, falling back to the bundled baseline only when no cache exists at all. *Rejected:*
  reverting a live promotion to the baked-in default the moment a device goes briefly offline.
- **No system polling (`updatePeriodMillis=0`).** The 30-min platform minimum wakes the device to
  fetch + decode and can't honor the finer `refreshInterval` anyway. The widget hydrates on
  placement / reboot / app-update + app `refresh()`. *Deferred:* a WorkManager job for battery-friendly
  periodic background refresh.
- **60fps push target, kept despite the IPC cost.** 30fps was tried and read as visibly steppy for this
  short eased spin. The frame-stats log measures *push cadence* (producer side), not displayed frames.
- **A single reused frame buffer.** The frame loop allocates **one** bitmap up front and rewrites it every
  frame (render the rotated wheel → push it → render the next angle into the *same* bitmap). The point is
  to allocate nothing per frame: the naïve approach creates a fresh ~410 KB bitmap 60×/sec, and the
  resulting garbage-collection pauses show up as **mid-spin stutter** — exactly what you don't want during
  a smooth animation. Reusing one buffer keeps the spin allocation-free, so there's no GC jank.
  This is safe because `partiallyUpdateAppWidget` is a synchronous Binder call and `Bitmap.writeToParcel`
  copies the pixels into the parcel *before it returns* — so by the time the next frame overwrites the
  buffer, the launcher already has its own copy (no tearing). *Rejected:* **double buffering** (two
  bitmaps swapped each frame). It was prototyped and A/B'd on-device against the single buffer; the single
  buffer showed no tearing and no frozen frames, so the second bitmap was pure overhead (extra ~410 KB and
  swap bookkeeping) and was dropped.

## Known limitations

- **Per-spin asset decode.** Each spin (and each `onUpdate`) re-decodes the four PNG layers from disk
  via `BitmapFactory.decodeFile` and re-scales the wheel, with no in-memory layer cache. This adds a
  little latency between the tap and the first frame, and decodes large source images at full
  resolution before downscaling (higher peak memory). *Out of scope* here; an in-memory decoded-layer
  cache keyed by asset id (plus `inSampleSize` downsampling) would remove both costs.
- **Wheel resolution cap (320²).** The wheel bitmap is capped to 320×320 to stay well under the ~1MB
  Binder transaction limit (which is also shared across in-flight transactions). On a large placed
  widget it's upscaled and looks softer. There's headroom to raise it (≈480² is still under the limit)
  if sharper large widgets matter more than transaction margin.
- **60fps target over RemoteViews is heavy.** Pushing ~410KB bitmaps at 60/sec is ~25MB/s of parcel
  copies through `system_server` into the launcher. 60 is nonetheless the target: **30fps was tried and
  read as visibly steppy** for this short eased spin on real devices. (Ties into "push cadence" above —
  some of those 60 pushes may be coalesced by the launcher.)
- **`segmentIndex` is geometric, not a prize** (see the config/API note above).
- **Spin duration is capped at 8s** (`MAX_DURATION_MS`) because it runs inside the AppWidgetProvider
  broadcast's `goAsync()` window (~10s ANR limit). A config asking for longer is clamped; genuinely long
  spins would need a foreground service / WorkManager.
- **No system polling (`updatePeriodMillis=0`).** The platform minimum is 30 min and each poll wakes the
  device to fetch + decode, so it's removed in favor of placement/reboot hydration + app-driven `refresh()`.
  Periodic background refresh that honors `refreshInterval` would be a WorkManager job (out of scope).
- **Offline past the hard TTL keeps the last-known-good remote config** (stale-if-error), rather than
  reverting to the bundled baseline — see *Design decisions* above.
- **iOS is a no-op stub.** The widget is Android-only; the iOS module exists only so the JS import
  resolves and every method is a safe no-op.

## License

MIT
