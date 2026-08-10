# C&C Generals Zero Hour — Android tablet optimization

Handoff for continuing in a fresh Claude Code session.

**Date:** 2026-08-10
**Target device:** Snapdragon 8-series flagship tablet (Adreno 7xx/8xx, arm64-v8a)
**Upstream:** [tarek369/GeneralsZH-Android](https://github.com/tarek369/GeneralsZH-Android)

---

## ⚠️ Read this first

The work was done in an **ephemeral cloud container**. The git clone and its commit
are gone. What survives:

- `generalszh-tablet-optimizations.patch` — the actual work, delivered as a chat
  attachment and committed alongside this doc.
- This document.

To resume: re-clone upstream and `git am` the patch. Details in
[Resuming](#resuming-in-a-new-session).

---

## Background

### The request

Originally phrased as "c@c general zero hour" — decoded as **Command & Conquer:
Generals – Zero Hour**. The ask: get the game running well on a tablet with full
touchscreen support.

### Legal position (settled)

- **The engine is open source.** EA released the full Generals + Zero Hour source
  under **GPL-3.0** at
  [electronicarts/CnC_Generals_Zero_Hour](https://github.com/electronicarts/CnC_Generals_Zero_Hour).
  Free to fork, build, modify.
- **The game assets are not.** The `.big` archives (art, audio, video, maps) remain
  EA copyright. They must come from a copy you own.
- The user confirmed they own the game on Steam:
  `D:\steam\steamapps\common\Command & Conquer Generals - Zero Hour`.
  **The assets never pass through the agent** — they go laptop → tablet directly.

### A port already existed

Do not rebuild this from scratch. `tarek369/GeneralsZH-Android` already runs the
real engine natively via **DXVK → Vulkan** (the first DXVK build for Android
aarch64), with SDL3 for windowing/input and OpenAL for audio.

**Known upstream gap: audio initializes but produces no sound.** Not addressed by
any of this work.

---

## Architecture (as it stands upstream)

```
SDLActivity (Java) → nativeRunMain → dlopen libmain.so → SDL_main()
                                          ↓
                        engine (500k LOC C++, D3D8 calls)
                                          ↓
                        DXVK (libdxvk_d3d8 / libdxvk_d3d9) → Vulkan
```

| Concern | Where |
|---|---|
| Android entry point | `android/app/src/main/java/me/generalsx/zh/GameActivity.java` |
| Native entry, resolution, asset extraction | `GeneralsMD/Code/Main/SDL3Main.cpp` |
| Touch → mouse gesture state machine | `GeneralsMD/Code/GameEngineDevice/Source/SDL3GameEngine.cpp` (~lines 138–390) |
| DXVK runtime config | `android/config/dxvk.conf` + `android/app/src/main/assets/dxvk.conf` |
| Gradle / NDK / CMake wiring | `android/app/build.gradle` |
| Porting notes & status | `android.md` |

Mobile code is gated on `SAGE_MOBILE` (iOS **and** Android); Android-only paths use
`__ANDROID__`.

Touch gestures already implemented upstream (left alone by this work): tap = LMB,
600 ms hold = RMB, drag = selection box, two-finger drag = camera pan, pinch = zoom.

---

## Changes made

All four are in the patch, one commit: `perf(android): tune for Snapdragon 8-series tablets`.

### 1. Render-height cap — the big performance win

**File:** `GeneralsMD/Code/Main/SDL3Main.cpp`

**Problem.** The mobile path requests a `SDL_WINDOW_HIGH_PIXEL_DENSITY` drawable and
drove the engine's internal resolution straight from the native pixel size. On a
OnePlus Pad 2 that's **3392×2400 = 8.1 megapixels per frame**, pushed through a
2003 fixed-function D3D8 pipeline re-emitted as Vulkan. The engine's fill-heavy
passes (terrain multi-texture blending, shroud, water reflection, particle
overdraw) scale linearly with pixel count. Sustained, it also drives thermal
throttling — which reads as a *framerate cliff* mid-match, not a steady low rate.

**Fix.** Cap the render height (default **1200**, i.e. 2× the engine's 600 px UI
design baseline) and derive width from the panel's true aspect ratio so the picture
still fills the screen. DXVK's presenter scales the smaller backbuffer up to the
swapchain; the panel scaler does the final blit for free. ~**4× fewer pixels** at
2400p.

New helpers: `gx_parse_render_height()`, `gx_resolve_render_height()`.

Resolution order (first hit wins):

1. `-renderheight <px>` command line
2. `SAGE_RENDER_HEIGHT` environment variable
3. `RenderHeight=` in `tablet.ini` (in the GameData dir)
4. Default `1200`

`native` disables the cap. Values clamp to ≥ 600 (stock window layouts break below
the 600 px design baseline).

Also corrected a stale comment above the block that claimed height stayed at 600
while the code actually used full native.

### 2. Bug found — the shipped `dxvk.conf` was never read

**File:** `GeneralsMD/Code/Main/SDL3Main.cpp`

DXVK reads `dxvk.conf` from the **process working directory** (which is the GameData
dir — `SDL3Main.cpp` `chdir()`s there at startup). But the startup code only ever
extracted **fonts** out of the APK. `android/app/src/main/assets/dxvk.conf` sat
inside the APK where nothing could `fopen()` it, so **every setting in it silently
fell back to desktop defaults.**

Added `gx_extract_apk_asset(assetPath, outPath)` — a general APK-asset extractor
modeled on the existing font loop — and wired it up for `dxvk.conf` and
`tablet.ini`. Neither is overwritten once present, so on-device edits survive.

> This bug affects the **stock upstream APK** too. Users can work around it without
> rebuilding by pushing a `dxvk.conf` into the GameData dir by hand.

### 3. DXVK tuning for Adreno

**Files:** `android/config/dxvk.conf`, `android/app/src/main/assets/dxvk.conf`
(kept identical — the `config/` copy is the source of truth)

| Setting | Value | Why |
|---|---|---|
| `d3d9.maxFrameLatency` | `1` | Default 3 is tuned for a mouse. Under touch your finger is *on* the selection box, so queued frames read as the box physically lagging the fingertip. |
| `d3d9.presentInterval` | `1` | Frames the compositor never shows are pure heat on a passively cooled device. |
| `dxvk.numCompilerThreads` | `3` | Default 0 = one thread per core, flooding all 8 mid-match and fighting the game thread for the prime core. |
| `d3d9.samplerLodBias` | `-0.5` | Recovers apparent sharpness lost to the render-height cap. |
| `d3d9.samplerAnisotropy` | `16` | Kept from upstream — RTS camera looks at terrain edge-on, worst case for trilinear. |
| `dxvk.enableGraphicsPipelineLibrary` | `Auto` | Qualcomm's `VK_EXT_graphics_pipeline_library` support is driver-version dependent; let DXVK detect. |

**Documented traps (do not enable on Android):**

- `dxvk.hideIntegratedGraphics` — would hide the *only* GPU present. No discrete
  fallback exists; device creation fails outright.
- `d3d9.maxAvailableMemory` — the engine picks its texture-detail tier from
  `GetAvailableTextureMem()`. Raising it on a unified-memory device talks the engine
  into a working set the app heap can't hold, and the OOM killer decides how that
  ends. Left at default, commented with guidance.

All option keys were **verified against upstream DXVK's `dxvk.conf`** before use.
Note `dxvk.enableStateCache` and `d3d9.cachedDynamicBuffers` are **absent** from
current DXVK and were deliberately not used.

### 4. Android-side performance and gesture handling

**File:** `android/app/src/main/java/me/generalsx/zh/GameActivity.java`

- **`setSustainedPerformanceMode(true)`** — without it the governor treats a match
  as a burst workload: boost to peak, chassis saturates, thermal management drops
  clocks hard. This asks for a ceiling the device can hold indefinitely.
- **`FLAG_KEEP_SCREEN_ON`** — an RTS runs long stretches with no touch input while
  the player watches a base build out. Exactly when the screen timeout fires.
- **Cutout rendering** (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`) — landscape
  game; letterboxing away a notch costs real sidebar space.
- **System gesture exclusion** (48 dp bands, left and right edges) — a drag-select
  starting at the screen edge is the same gesture shape as Android's back swipe.
  Without this the player gets thrown out of the match instead of selecting units.
  Re-applied on every layout pass because the rects are dropped on re-layout (SDL
  recreates its surface on rotation and on resume).

### 5. New config file: `tablet.ini`

**Files:** `android/config/tablet.ini`, `android/app/src/main/assets/tablet.ini`

Documented, user-editable tuning file extracted to the GameData dir on first launch
so `RenderHeight` can be changed on-device without rebuilding.

---

## Verification status

### Verified

| Check | Result |
|---|---|
| `GameActivity.java` compiles against real `android.jar` (API 35) | ✅ clean |
| `Window.setSustainedPerformanceMode` API level | 24 = `minSdk`, no guard needed ✅ |
| `View.setSystemGestureExclusionRects` API level | 29 → `Build.VERSION_CODES.Q` guard correct ✅ |
| `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` API level | 28 → `Build.VERSION_CODES.P` guard correct ✅ |
| Unknown CLI flags are skipped, not rejected (`-renderheight` safe) | ✅ `CommandLine.cpp:1455-1458` |
| `__argc` / `__argv` visible at helper's file scope | ✅ defined `SDL3Main.cpp:108-109` |
| Preprocessor `#if`/`#endif` balance | ✅ depth 0 |
| Brace/paren delta vs original | ✅ zero (pre-existing imbalance is a regex artifact of platform `#ifdef` blocks) |
| DXVK option keys exist upstream | ✅ checked against upstream `dxvk.conf` |

API levels were confirmed against the SDK's own
`platforms/android-35/data/api-versions.xml`, not from memory.

### NOT verified

- ❌ **The native C++ does not compile-verify.** No NDK in the environment; a full
  build needs vcpkg, Meson/Ninja, a DXVK build and 500k LOC. **Treat the C++ as
  reviewed-but-unbuilt — this is the top priority for the next session.**
- ❌ **Nothing tested on device.** No tablet attached; the container is x86_64 Linux.
- ❌ **Performance claims are reasoning, not measurement.** The ~4× pixel reduction
  is arithmetic; the framerate and thermal effects are inference from how the engine
  and SoC behave, not benchmarks.

---

## Resuming in a new session

```bash
git clone https://github.com/tarek369/GeneralsZH-Android.git
cd GeneralsZH-Android
git checkout -b tablet-optimizations
git am < ../generalszh-tablet-optimizations.patch
```

### Build requirements

Per `android/app/build.gradle`: **NDK 27.1.12297006**, `compileSdk 35`, `minSdk 24`,
arm64-v8a only, CMake 3.25+. Key flags: `SAGE_USE_SDL3=ON`, `SAGE_USE_OPENAL=ON`,
`SAGE_DXVK_USE_LOCAL_FORK=ON`, `RTS_BUILD_OPTION_FFMPEG=OFF`,
`RTS_GAMEMEMORY_ENABLE=OFF`.

Requires the DXVK submodule:
`git submodule update --init references/fbraz3-dxvk`

`-PSAGE_SKIP_NATIVE_BUILD=true` repackages the APK from pre-staged `.so` files
without re-running the native build.

### Getting game assets onto the tablet

From the user's Steam install (`D:\steam\steamapps\common\Command & Conquer Generals - Zero Hour`):

```
adb shell mkdir -p /sdcard/Android/data/me.generalsx.zh/files/GameData/Data
adb push "<install>/Data/." /sdcard/Android/data/me.generalsx.zh/files/GameData/Data/
```

Needs **both** base Generals and ZH archives — the ZH engine loads base `INI.big`
for e.g. `Weapon.ini`. On Android 11+ MTP often can't write into `Android/data`;
`adb push` still can.

Required: `INI.big`, `INIZH.big`, `Textures.big`, `TexturesZH.big`, `Audio.big`,
`AudioZH.big`, `Music.big`, `MusicZH.big`, `MapsZH.big`, `Terrain.big`,
`TerrainZH.big`, `W3D.big`, `W3DZH.big`, `English.big`, `EnglishZH.big`,
`Window.big`, `WindowZH.big`, `ShadersZH.big`, plus Speech archives.

---

## Suggested next steps

1. **Compile-verify the native changes** with the NDK. Highest priority — the only
   unverified part of the diff.
2. **Build and install the patched APK**, then measure. Try `RenderHeight` at
   900 / 1200 / 1440 / `native` and compare.
3. **Confirm the `dxvk.conf` extraction actually fires** — check logcat for
   `assets: extracted 'dxvk.conf'` and confirm DXVK picks up the settings.
4. **Validate gesture exclusion on-device** — drag-select from the extreme screen
   edge and confirm the back gesture doesn't fire.
5. **Optional: fix the missing audio** (upstream gap — OpenAL/Oboe path).
6. **Optional: richer touch UX** — edge-scroll zones, control-group bar, larger hit
   targets, double-tap select-all-of-type.

### Not done

- Never pushed to a GitHub repo. Needs a decision on creating a fork under the
  user's account (`u4e-glitch/GeneralsZH-Android` was proposed; **not** created —
  the user's GitHub scope in that session was `u4e-glitch/graphify` only).
- Audio remains broken (upstream).
- No on-device testing of any kind.

---

## Licensing note

The engine is **GPL-3.0**. Any distribution of a modified build must carry source.
Game assets are separately licensed and must not be redistributed — every user
supplies their own from a copy they own.
