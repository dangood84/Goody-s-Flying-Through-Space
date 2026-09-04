# How this screensaver works

This note is for an automation tester who wants to see how a small Java Swing application is structured: where it starts, who owns state, who paints pixels, and how the stars move.

You do not need to be a Swing expert or a 3D programmer. The same ideas show up in many GUI and game-like apps: an entry point, a UI thread, a model, a view, and a timed loop that updates positions then redraws.

There is **no 3D engine**. Stars are a few numbers in an array. Each tick the depth (`z`) gets smaller. Each paint those numbers are projected onto the 2D panel with a divide. That is the whole “flying through space” trick.

## Mental model

```
main()
  → parse flags
  → queue work on the Swing Event Dispatch Thread (EDT)
      → load ScreensaverConfig (state)
      → either SettingsDialog (settings UI)
         or StarfieldFrame (full-screen window)
              → StarfieldPanel (timer + paint)
```

| Layer | Class | Tester-friendly analogy |
|-------|--------|-------------------------|
| Entry / routing | `StarfieldSaver` | Test runner that picks “config” vs “run” from CLI |
| State | `ScreensaverConfig` | Test data / fixture that is saved and reloaded |
| Settings UI | `SettingsDialog` | Form that writes into the fixture |
| Window shell | `StarfieldFrame` | Full-screen host + “abort on input” |
| Animation view | `StarfieldPanel` | The thing that actually moves and draws stars |

Swing is **event-driven**. Almost everything after `main` runs on one thread: the **Event Dispatch Thread (EDT)**. Clicks, timer ticks, and `paintComponent` all happen there. That is why the animation uses `javax.swing.Timer` instead of a raw `while (true)` loop on a background thread.

---

## 1. Entry point and execution lifecycle

### Where `main` lives

The real entry point is `StarfieldSaver.main(String[] args)`.

`FlyingThroughSpace.main` only forwards to that method so the project-style class name still works.

`StarfieldSaver` has a private constructor. It is never instantiated. It is a **static bootstrap** class: parse arguments, then start the UI.

### Lifecycle, step by step

1. **JVM starts** and calls `main`.
2. **`LaunchMode.fromArgs(args)`** maps Windows-style and GNU-style flags:
   - `/s` or `--fullscreen` → full-screen saver
   - `/c` or `--config` (also the default with no args) → settings dialog
   - `/p` or `--preview` → exit immediately (Settings preview pane; this Java UI does not embed into a native HWND)
3. If the mode is `PREVIEW`, `main` returns. The process ends. No window.
4. Otherwise **`SwingUtilities.invokeLater(...)`** posts a Runnable to the EDT.  
   `main` itself must not create Swing windows. They are not thread-safe.
5. On the EDT:
   - install the system look and feel
   - **`ScreensaverConfig.load()`** reads last-saved preferences (or defaults)
   - `switch` on mode:
     - `FULLSCREEN` → `new StarfieldFrame(config, () -> System.exit(0)).showFullScreen()`
     - `CONFIG` → `new SettingsDialog(config).setVisible(true)`
6. After the window is showing, the process stays alive because Swing keeps a **non-daemon AWT thread** running until `System.exit(0)` or the last window is disposed (settings close also calls `System.exit(0)`).

### Two user journeys

**Settings first** (`./run.sh` or `--config`):

```
EDT creates SettingsDialog
  → user edits controls → each change writes ScreensaverConfig and save()
  → live StarfieldPanel in the dialog already animates (same renderer as full screen)
  → Close → windowClosed → save → System.exit(0)
  → Start screensaver → save, hide/dispose dialog, StarfieldFrame full screen
       → any key or ~12px mouse move → stop timer, leave exclusive full screen, System.exit(0)
```

**Saver first** (`--fullscreen` / `/s`):

```
EDT creates StarfieldFrame immediately
  → exclusive full screen if the graphics device supports it
  → StarfieldPanel timer starts when the panel is realized (addNotify)
  → input → System.exit(0)
```

### Why testers care

- **CLI is the feature flag.** Automating “open settings” vs “open saver” is `java ... StarfieldSaver --config` vs `--fullscreen`.
- **Preferences survive process restarts.** A test that changes warp speed and relaunches should see the same speed. Storage is `java.util.prefs.Preferences` (OS user prefs, not a file in the repo).
- **Exit is process-level** on the full-screen path (`System.exit(0)`), not “navigate back to a page.”
- **The settings preview is the real renderer.** If stars move in the dialog, the same class will move them full screen. You are not testing a fake stub.

---

## 2. Main classes and responsibilities

This is a **separation of UI vs state**, not a full MVC framework. There is no database and no service layer.

### `StarfieldSaver` — composition root

- Parses argv
- Chooses which window to open
- Does **not** draw stars or store `(x, y, z)` coordinates

### `ScreensaverConfig` — state management

Holds the starfield *look and warp*, not the current star positions:

- number of stars (`starCount`, 20–800)
- warp speed (`warpSpeed`, 1–20)
- maximum star size in pixels (`maxStarSize`, 1–8)
- warp trails on/off
- star colour, background colour

**Load / save:** `Preferences.userNodeForPackage(ScreensaverConfig.class)`. Keys are string names like `"starCount"` and `"warpSpeed"`. Colours are stored as `Color.getRGB()` ints.

**Copy:** `copy()` snapshots state when launching full screen so the saver is not coupled to the dialog’s later edits (the dialog is disposed anyway).

**Clamping:** setters use `Math.clamp` so sliders cannot push illegal counts/speeds/sizes.

This class is the closest thing to a **model**. It has no Swing widgets and no per-star data.

### `SettingsDialog` — settings UI

- A `JDialog` with form controls (sliders, checkbox, `JColorChooser`)
- Writes into the **same** `ScreensaverConfig` instance it was given
- `persist(Runnable)` = mutate config + `config.save()` on each change
- Hosts a small `StarfieldPanel` as a **live preview** (same class as full screen)
- Does not compute star positions; it only changes config fields the panel reads while ticking and painting

If the tester moves the “Number of stars” slider, `StarfieldPanel.ensureStars()` notices `stars.length != config.getStarCount()` and rebuilds the array on the next tick/paint. That is why density changes live.

### `StarfieldFrame` — window chrome and wake-on-input

- Undecorated `JFrame`, hidden cursor, always on top
- Puts a `StarfieldPanel` in `setContentPane`
- `showFullScreen()` uses `GraphicsDevice.setFullScreenWindow` when supported, otherwise maximized
- **Does not paint the stars.** It listens for keys and mouse motion and then shuts down
- Mouse “wake”: first motion is recorded; later motion of 12 pixels or more exits (so the initial cursor warp does not instantly quit)

### `StarfieldPanel` — rendering + animation loop

- Double-buffered `JPanel`
- Owns **transient animation state**: the `Star[]` array and `lastNanos`
- Owns the `javax.swing.Timer`
- `onFrame` updates depth (`z`); `paintComponent` is the only place `Graphics2D` draws
- Reads `ScreensaverConfig` every tick and every paint, so the preview updates when sliders change

Nested type `StarfieldPanel.Star` is a tiny struct: world `x, y, z`, plus last projected `prevScreenX/Y` for trails. It is not a public API.

### `FlyingThroughSpace`

Compatibility alias for `main`. No extra behaviour.

### What is *not* a class

There is no `Camera`, `Scene`, or `Mesh`. Perspective is a formula in `paintComponent`. The “objects” on screen are filled rectangles (and optional lines) derived from each `Star`.

---

## 3. How the render / animation loop works

This is **not** a classic game loop on a worker thread (`while (running) { update(); render(); }`).

It is a **Swing timer loop** on the EDT:

```
javax.swing.Timer (~16 ms, 60 FPS)
    → onFrame()           // update each star.z
        → repaint()       // ask Swing to paint later
            → paintComponent()  // project 3D → 2D and draw
```

### Timer setup

```text
TARGET_FPS = 60
FRAME_DELAY_MS = round(1000 / 60)  → 17 ms
timer = new Timer(FRAME_DELAY_MS, event -> onFrame())
timer.setCoalesce(true)   // if the EDT is busy, collapse pending ticks
timer.setRepeats(true)
```

`Timer` fires `ActionEvent`s on the **EDT**, so `onFrame` may touch Swing safely.

### When it starts and stops

| Hook | Meaning |
|------|--------|
| `addNotify()` | Panel is attached to a realized window → `start()` |
| `removeNotify()` | Panel is taken off screen → `stop()` |
| `StarfieldFrame.exitScreensaver()` | Also calls `panel.stop()` before dispose |

The settings preview starts automatically when the dialog is shown, for the same reason: the preview `StarfieldPanel` gets `addNotify`.

### `repaint()` vs `paintComponent`

- `repaint()` does **not** draw immediately. It marks the component dirty. Swing later calls `paint` → `paintComponent`.
- All drawing is in `paintComponent(Graphics)`.
- The panel is `setDoubleBuffered(true)` so Swing paints to an off-screen buffer then blits it, which reduces flicker.
- Antialiasing is **off**. Stars are crisp squares, like the old VGA screensaver, not smooth circles.

### Why not `Thread.sleep` in a loop?

A blocking loop on the EDT would freeze the UI (no paints, no key events). A loop on another thread would have to call `SwingUtilities.invokeLater` to paint, which is easy to get wrong. The timer is the Swing-native “game loop.”

### Frame timing vs movement

The timer *aims* at 60 Hz, but it can jitter. Movement is **not** “subtract 0.01 from z every tick.” It uses **elapsed real time** so warp stays close to the chosen speed even if a tick is late (see next section).

### Update vs draw (important split)

| Method | Mutates | Draws |
|--------|---------|-------|
| `onFrame` | `star.z` (and respawn if too close) | no |
| `paintComponent` | `prevScreenX/Y` for trails; respawn if off-screen | yes |

A tester debugging “stars jump” should breakpoint `onFrame`. A tester debugging “stars are the wrong size/colour” should breakpoint `paintComponent`.

---

## 4. Position math on each tick

Each star lives in a simple 3D volume:

- **`x`, `y`** — world position, each in roughly `[-1, 1]`
- **`z`** — depth. `1.0` is far (near the vanishing point). `0.04` is too close; the star is recycled

The camera sits at the origin looking toward `+z` in the sense that **smaller `z` means closer to the viewer**. Stars do not move in `x` or `y`. Only `z` decreases. On screen they still fly outward, because of perspective (next).

### Warp (in `onFrame`)

```text
now = System.nanoTime()
elapsedSeconds = (now - lastNanos) / 1_000_000_000
elapsedSeconds = min(elapsedSeconds, 0.05)     // cap so a pause does not jump

dz = (warpSpeed / 10.0) * 1.0 * elapsedSeconds
star.z = star.z - dz
if star.z <= 0.04
    respawn at the far plane
```

`warpSpeed` 10 means **1.0 z-unit per second**. Defaults: speed 8, so a star takes a bit over a second to travel from `z = 1` down to `z = 0.04`.

Example: warp 8 and a 16.7 ms frame → `dz ≈ 0.8 * 0.0167 ≈ 0.013`.

The `0.05` cap (50 ms) stops a huge leap if the app was stalled (breakpoint, sleep, window not shown).

First tick only stores `lastNanos` and `repaint()`s; it does not move, so the first delta is not “from JVM start.”

### Perspective projection (in `paintComponent`)

2D screen position from 3D:

```text
cx = panelWidth  / 2
cy = panelHeight / 2
scale = 0.18 * min(panelWidth, panelHeight)

sx = cx + (star.x / star.z) * scale
sy = cy + (star.y / star.z) * scale
```

Why this looks like flying:

- When `z` is large (far), `x / z` is small → the star sits near the **centre**.
- When `z` shrinks (near), `x / z` grows → the same star races toward the **edges**.
- Divide-by-`z` is the classic pinhole / “1/z” perspective. `FOCAL = 0.18` only scales how wide the field of view feels.

The same formula works in the 600×200 settings preview and in full screen because `cx`, `cy`, and `scale` are derived from the panel size.

### Size and brightness from depth

```text
closeness = 1 - (z - Z_NEAR) / (Z_FAR - Z_NEAR)    // 0 = far, 1 = near
size      = round(1 + closeness * (maxStarSize - 1))   // at least 1 px
colour    = fade(background, starColor, 0.22 + 0.78 * closeness)
```

Far stars are dim 1×1 pixels. Near stars are larger and closer to the chosen star colour. That is why the field has depth even though every star is a rectangle.

### Warp trails

If trails are on and the star was drawn last frame:

```text
drawLine(prevScreenX, prevScreenY, sx, sy)
fillRect around (sx, sy)
then prevScreenX, prevScreenY = sx, sy
```

At low warp the line is a couple of pixels (looks like a dot). At high warp the star moves farther between frames, so the line becomes a streak — the classic Windows “warp speed” look. No extra physics; it is just last projected point to this projected point.

### Recycle / respawn

A star is reused, not deleted, when:

- `z` reaches `Z_NEAR` (0.04) during `onFrame`, or
- the projected `(sx, sy)` is off the panel (plus an 8 px margin) during paint

```text
x = random in [-1, 1]
y = random in [-1, 1]
z = far plane (about 0.88 … 1.0)     // or scattered 0.04 … 1.0 on first fill
hasPrev = false                      // no trail from the old position
```

First fill (`scatterDepth = true`) spreads stars through the whole depth so the screen is not empty, then a wave. Later respawns put stars back in the distance so they fly in again.

`ensureStars()` rebuilds the whole array only when `config.getStarCount()` changes.

### Who updates what

| Value | Updated when | Role |
|-------|----------------|------|
| `star.z` | every timer tick | depth / “approach the camera” |
| `star.x`, `star.y` | only on respawn | which spoke of the starfield |
| `sx`, `sy` | every paint | 2D position from `x/z`, `y/z` |
| `prevScreenX/Y` | every paint | trail start |
| config fields | settings controls | density, warp, size, colours; **not** per-star `z` |

Speed tests: change the Warp speed slider and the same `z -= (warpSpeed / 10) * dt` formula makes stars approach faster, so they also streak farther per frame. You are not changing a “step size” constant; you are changing the velocity term on `z`.

---

## Quick map of files

```
src/main/java/com/goody/screensaver/
  StarfieldSaver.java       # main, flags, EDT bootstrap
  FlyingThroughSpace.java   # main alias
  ScreensaverConfig.java    # model + Preferences
  SettingsDialog.java       # settings view
  StarfieldFrame.java       # full-screen shell + input
  StarfieldPanel.java       # timer, z math, 1/z projection, Graphics2D
```

If you are tracing in a debugger, put breakpoints on `StarfieldSaver.main`, `StarfieldPanel.onFrame`, and `StarfieldPanel.paintComponent`. You will see: **tick decreases `z` → `repaint` → paint divides by `z` and draws**.
