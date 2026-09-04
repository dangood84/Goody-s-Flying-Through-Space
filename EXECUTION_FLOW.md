# Execution flow: from `main` to a drawn frame

A step-by-step trace of what happens from `public static void main(String[] args)` through screen initialisation and timer startup, down to how individual frames are calculated and drawn.

Default launch (`./run.sh`) opens settings; `--fullscreen` skips the dialog. The starfield itself is the same either way: a `StarfieldPanel` gets a peer, a timer starts, then update and draw alternate on the EDT.

Two threads matter after startup:

- **main** — parses flags, posts work, then returns
- **EDT** (Event Dispatch Thread) — creates windows, starts the timer, ticks, paints, handles keys

---

## Phase A — `main` (not the EDT yet)

1. JVM calls `StarfieldSaver.main(String[] args)` (`FlyingThroughSpace.main` only forwards here).
2. `LaunchMode.fromArgs` strips `/`, `-`, `--` and maps `s`/`fullscreen`, `c`/`config`, `p`/`preview`. Last match wins. No args → `CONFIG`.
3. If mode is `PREVIEW`, `main` returns. Process ends. No window.
4. Otherwise `SwingUtilities.invokeLater(...)` queues a Runnable on the EDT. Swing is not thread-safe, so nothing below is created on `main`.
5. `main` returns. The JVM stays up because AWT has started a **non-daemon** toolkit thread.

```java
public static void main(String[] args) {
    LaunchMode mode = LaunchMode.fromArgs(args);
    if (mode == LaunchMode.PREVIEW) {
        return;
    }

    SwingUtilities.invokeLater(() -> {
        installLookAndFeel();
        ScreensaverConfig config = ScreensaverConfig.load();
        switch (mode) {
            case FULLSCREEN -> new StarfieldFrame(config, () -> System.exit(0)).showFullScreen();
            case CONFIG, PREVIEW -> new SettingsDialog(config).setVisible(true);
        }
    });
}
```

---

## Phase B — first EDT work: look, config, which window

6. EDT runs the Runnable.
7. `installLookAndFeel()` asks `UIManager` for the OS widgets (Aqua, Windows, …). Failure is ignored; Metal stays.
8. `ScreensaverConfig.load()` builds a fresh config, overlays `Preferences` keys (`starCount`, `warpSpeed`, colours as packed ARGB), then `setStarCount` / `setWarpSpeed` / `setMaxStarSize` clamp ranges.
9. Switch:
   - `FULLSCREEN` → `new StarfieldFrame(config, () -> System.exit(0)).showFullScreen()`
   - `CONFIG` → `new SettingsDialog(config).setVisible(true)`  
     That dialog also embeds a `StarfieldPanel`. From step 12 onward the **preview** follows the same panel path; the rest of this trace is **full screen** (`/s` or **Start screensaver**).

**Start screensaver** from the dialog: `config.save()`, `launchingScreensaver = true` (so `windowClosed` does not `System.exit`), dispose the dialog, then `new StarfieldFrame(config.copy(), ...).showFullScreen()`. `copy()` snapshots so the saver is not sharing a live object.

```java
private void launchScreensaver() {
    config.save();
    launchingScreensaver = true;
    setVisible(false);
    dispose();
    new StarfieldFrame(config.copy(), () -> System.exit(0)).showFullScreen();
}
```

---

## Phase C — screen initialisation (`StarfieldFrame`)

10. `StarfieldFrame` constructor:
    - undecorated, not resizable, always on top, `DO_NOTHING_ON_CLOSE`
    - invisible 1×1 ARGB cursor
    - `new StarfieldPanel(config)` (timer is **created**, not started)
    - `setContentPane(panel)`
    - key + mouse-motion listeners on **both** frame and panel

```java
public StarfieldFrame(ScreensaverConfig config, Runnable onExit) {
    super("Goody's Flying Through Space");
    this.onExit = onExit;
    this.device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

    setUndecorated(true);
    setResizable(false);
    setAlwaysOnTop(true);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setCursor(invisibleCursor());
    setFocusable(true);

    StarfieldPanel panel = new StarfieldPanel(config);
    setContentPane(panel);
    bindWakeListeners(panel);
}
```

11. `StarfieldPanel` constructor:
    - opaque + double-buffered (Swing paints to an off-screen image, then blits)
    - `new Timer(17, event -> onFrame())` (~60 FPS), `setCoalesce(true)`, `setRepeats(true)`
    - `stars = new Star[0]`, `lastNanos = 0` — **no stars yet**, timer not running

```java
public StarfieldPanel(ScreensaverConfig config) {
    this.config = config;
    setOpaque(true);
    setDoubleBuffered(true);
    setBackground(config.getBackgroundColor());
    setFocusable(true);

    timer = new Timer(FRAME_DELAY_MS, event -> onFrame());
    timer.setCoalesce(true);
    timer.setRepeats(true);
}
```

12. `showFullScreen()`:
    - if the device allows it: `device.setFullScreenWindow(this)` (exclusive mode; this also shows the window)
    - else: maximized + `setVisible(true)`
    - `toFront` / `requestFocus` so keys actually arrive

```java
public void showFullScreen() {
    if (device.isFullScreenSupported()) {
        device.setFullScreenWindow(this);
    } else {
        setExtendedState(MAXIMIZED_BOTH);
        setVisible(true);
    }
    toFront();
    requestFocus();
    getContentPane().requestFocusInWindow();
}
```

13. Showing the frame **realizes** the component tree. AWT creates native peers. That calls `StarfieldPanel.addNotify()`.
14. `addNotify()` → `super.addNotify()` then `start()`:
    - `lastNanos = 0`
    - `timer.start()` — Swing schedules the first `ActionEvent` in ~17 ms **on the EDT**

```java
@Override
public void addNotify() {
    super.addNotify();
    start();
}

public void start() {
    lastNanos = 0L;
    if (!timer.isRunning()) {
        timer.start();
    }
}
```

15. Swing may paint once before the first timer tick (empty or first `paintComponent`). Either way, the loop below is what keeps the field moving.

---

## Phase D — timer startup and the repeating loop

Every ~17 ms the EDT does **update**, then later **draw**. `repaint()` does not draw; it marks the panel dirty. Swing then calls `paint` → `paintComponent`.

```
Timer (EDT)
  → onFrame()          // mutate z
      → repaint()      // request a paint
          → paintComponent()  // project and draw
  → (wait ~17 ms)
  → onFrame() …
```

---

## Phase E — first timer tick (baseline, no motion)

16. `onFrame()` runs.
17. If width/height are still 0, return (no `repaint`). Unlikely after `addNotify`.
18. `ensureStars()`: `stars.length` is 0, config says e.g. 250 → allocate `Star[250]`, `respawn(star, true)` for each:
    - `x, y` random in `[-1, 1]`
    - `z` scattered through `[0.04, 1.0]` so the screen is full, not one incoming wave
    - `hasPrev = false`
19. `lastNanos == 0` → store `System.nanoTime()`, `repaint()`, **return**. No `z` change. First delta must not be “since JVM start.”

```java
private void onFrame() {
    int width = getWidth();
    int height = getHeight();
    if (width <= 0 || height <= 0) {
        return;
    }

    ensureStars();

    long now = System.nanoTime();
    if (lastNanos == 0L) {
        lastNanos = now;
        repaint();
        return;
    }

    double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
    lastNanos = now;
    elapsedSeconds = Math.min(elapsedSeconds, 0.05);

    double dz = (config.getWarpSpeed() / 10.0) * Z_PER_SECOND_AT_SPEED_10 * elapsedSeconds;
    for (Star star : stars) {
        star.z -= dz;
        if (star.z <= Z_NEAR) {
            respawn(star, false);
        }
    }
    repaint();
}
```

```java
private void ensureStars() {
    int count = config.getStarCount();
    if (stars.length == count) {
        return;
    }
    stars = new Star[count];
    for (int i = 0; i < count; i++) {
        stars[i] = new Star();
        respawn(stars[i], true);
    }
}

private void respawn(Star star, boolean scatterDepth) {
    star.x = random.nextDouble() * 2.0 - 1.0;
    star.y = random.nextDouble() * 2.0 - 1.0;
    if (scatterDepth) {
        star.z = Z_NEAR + random.nextDouble() * (Z_FAR - Z_NEAR);
    } else {
        star.z = Z_FAR - random.nextDouble() * 0.12;
    }
    star.hasPrev = false;
}
```

---

## Phase F — first draw (`paintComponent`)

20. Swing calls `paintComponent(Graphics g)` (often via the double-buffer image).
21. `g.create()` → `Graphics2D`; `dispose()` in `finally` so stroke/hints do not leak.
22. Antialiasing **off** (crisp 1×1 squares).
23. Fill the panel with `config.getBackgroundColor()`.
24. `ensureStars()` is a no-op if the count is unchanged.
25. Camera for this panel size:
    - `cx, cy` = centre
    - `scale = 0.18 * min(width, height)`
26. For each star:
    1. **Project:** `sx = cx + (x / z) * scale`, `sy = cy + (y / z) * scale`  
       Large `z` → near centre. Small `z` → toward the edges. `x` and `y` do not move after spawn.
    2. If `(sx, sy)` is off-screen (+ 8 px), `respawn(..., false)` (far plane, no trail) and skip draw.
    3. `closeness` from `z` (0 far … 1 near) → pixel `size` and faded colour.
    4. Trails skip on first draw (`hasPrev` is false).
    5. `fillRect` for the square.
    6. Store `prevScreenX/Y = sx/sy`, `hasPrev = true` for the next frame’s streak.
27. `g2.dispose()`. Swing blits the buffer to the screen.

```java
@Override
protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        int width = getWidth();
        int height = getHeight();
        g2.setColor(config.getBackgroundColor());
        g2.fillRect(0, 0, width, height);

        if (width <= 0 || height <= 0) {
            return;
        }

        ensureStars();

        double cx = width / 2.0;
        double cy = height / 2.0;
        double scale = FOCAL * Math.min(width, height);
        Color starColor = config.getStarColor();
        Color background = config.getBackgroundColor();
        boolean trails = config.isWarpTrails();
        int maxSize = config.getMaxStarSize();

        for (Star star : stars) {
            double sx = cx + (star.x / star.z) * scale;
            double sy = cy + (star.y / star.z) * scale;

            if (offScreen(sx, sy, width, height, 8)) {
                respawn(star, false);
                continue;
            }

            double closeness = 1.0 - (star.z - Z_NEAR) / (Z_FAR - Z_NEAR);
            closeness = Math.clamp(closeness, 0.0, 1.0);
            int size = Math.max(1, (int) Math.round(1.0 + closeness * (maxSize - 1)));
            Color color = fade(background, starColor, 0.22 + 0.78 * closeness);

            g2.setColor(color);
            if (trails && star.hasPrev) {
                float stroke = Math.max(1f, size * 0.65f);
                g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                g2.drawLine(
                        (int) Math.round(star.prevScreenX),
                        (int) Math.round(star.prevScreenY),
                        (int) Math.round(sx),
                        (int) Math.round(sy));
            }
            int left = (int) Math.round(sx) - size / 2;
            int top = (int) Math.round(sy) - size / 2;
            g2.fillRect(left, top, size, size);

            star.prevScreenX = sx;
            star.prevScreenY = sy;
            star.hasPrev = true;
        }
    } finally {
        g2.dispose();
    }
}
```

---

## Phase G — every later tick (this is “a frame”)

28. **Update (`onFrame`)**
    - `dt = min((now - lastNanos) / 1e9, 0.05)`
    - `dz = (warpSpeed / 10.0) * 1.0 * dt`  
      Warp 8, ~16.7 ms → `dz ≈ 0.013`
    - every star: `z -= dz`; if `z <= 0.04`, respawn on the far plane
    - `repaint()`
29. **Draw (`paintComponent`)** — same as phase F, plus: if trails are on, `drawLine(prevScreen, current)` then `fillRect`. Low warp ≈ a dot; high warp ≈ a streak. Then overwrite `prevScreen*`.

That pair (28 then 29) repeats until input.

---

## Phase H — shutdown

30. Key, or mouse move ≥ 12 px from the **first** sample (the first sample is ignored — exclusive mode often fakes a motion event).
31. `exitScreensaver()` once (`exited` guard): `panel.stop()` (timer off, `lastNanos = 0`), `setFullScreenWindow(null)` to give the desktop back, `dispose()`, `System.exit(0)`.
32. Disposing the frame calls `removeNotify()` → `stop()` again (idempotent).

```java
private void exitScreensaver() {
    if (exited) {
        return;
    }
    exited = true;

    if (getContentPane() instanceof StarfieldPanel panel) {
        panel.stop();
    }
    if (device.getFullScreenWindow() == this) {
        device.setFullScreenWindow(null);
    }
    dispose();
    onExit.run();
}

@Override
public void removeNotify() {
    stop();
    super.removeNotify();
}
```

---

## One-line map

`main` → `invokeLater` → load config → `StarfieldFrame.showFullScreen` → `addNotify` → `timer.start` → **`onFrame` decreases `z`** → **`repaint`** → **`paintComponent` divides by `z` and draws squares**.

Debugger: `StarfieldSaver.main`, `StarfieldPanel.addNotify`, `onFrame`, `paintComponent`. First `onFrame` only stamps time; motion starts on the second tick.

See also `WORKINGS.md` for class responsibilities and the perspective math in more detail.
