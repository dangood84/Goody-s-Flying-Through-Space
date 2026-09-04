package com.goody.screensaver;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Random;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * View + animation loop. Reads appearance/warp from {@link ScreensaverConfig}
 * on every tick and paint (so the settings preview updates live) and keeps only
 * <em>transient</em> motion state here: the {@link Star} array and timestamps.
 *
 * <p>There is no 3D engine. Each star is {@code (x, y, z)} in a unit volume.
 * {@code onFrame} only decreases {@code z}; {@code paintComponent} projects
 * with {@code x/z} so the same star races toward the screen edges as it
 * approaches — the classic Starfield Simulation trick.
 *
 * <p>Swing does not run a {@code while (true)} game loop. A {@link Timer}
 * posts {@code ActionEvent}s on the EDT; {@code onFrame} mutates {@code z}
 * then calls {@code repaint()}, which later causes {@code paintComponent}.
 */
public final class StarfieldPanel extends JPanel {

    static final int TARGET_FPS = 60;
    private static final int FRAME_DELAY_MS = Math.round(1000f / TARGET_FPS);

    /** Depth of a newly spawned star (far from the camera). */
    private static final double Z_FAR = 1.0;
    /** Recycle the star when it gets this close to the camera. */
    private static final double Z_NEAR = 0.04;
    /** How strongly perspective spreads stars from the centre. */
    private static final double FOCAL = 0.18;
    /** Warp speed 1…20 mapped to z-units per second. Speed 10 → 1.0 z/s. */
    private static final double Z_PER_SECOND_AT_SPEED_10 = 1.0;

    private final ScreensaverConfig config;
    private final Timer timer;
    private final Random random = new Random();

    /** Recycled pool; length tracks {@code config.getStarCount()}, not a growing list. */
    private Star[] stars = new Star[0];
    /** Previous {@link System#nanoTime()} sample; 0 means “no delta yet”. */
    private long lastNanos;

    public StarfieldPanel(ScreensaverConfig config) {
        this.config = config;
        // Opaque + double-buffered: Swing paints into an off-screen image then blits it,
        // which avoids flicker when we fill the background every frame.
        setOpaque(true);
        setDoubleBuffered(true);
        setBackground(config.getBackgroundColor());
        setFocusable(true);

        // javax.swing.Timer fires on the EDT (unlike java.util.Timer). Coalesce
        // drops extra ticks if the EDT was busy so we do not queue a backlog of frames.
        timer = new Timer(FRAME_DELAY_MS, event -> onFrame());
        timer.setCoalesce(true);
        timer.setRepeats(true);
    }

    public void start() {
        lastNanos = 0L;
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    public void stop() {
        timer.stop();
        lastNanos = 0L;
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    /**
     * Called by AWT when this panel is plugged into a realized window (peer created).
     * That is the safe moment to start animating: width/height are becoming meaningful
     * and the panel will actually receive paint events.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    /** Mirror of addNotify: stop the timer so a hidden/disposed panel does not keep the EDT busy. */
    @Override
    public void removeNotify() {
        stop();
        super.removeNotify();
    }

    /**
     * Update pass. Does not draw. Mutates each {@code star.z}, then {@code repaint()}
     * marks the component dirty; Swing will call {@code paintComponent} later on this
     * same EDT.
     */
    private void onFrame() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        ensureStars();

        long now = System.nanoTime();
        if (lastNanos == 0L) {
            // First tick: establish a baseline so the first delta is not “since JVM start”.
            lastNanos = now;
            repaint();
            return;
        }

        double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        // Cap dt so a breakpoint, sleep, or stalled EDT cannot fling every star to z=0.
        elapsedSeconds = Math.min(elapsedSeconds, 0.05);

        // Velocity * time, not a fixed “z per tick”, so warp 8 stays honest if the
        // timer jitters (17 ms vs 25 ms).
        double dz = (config.getWarpSpeed() / 10.0) * Z_PER_SECOND_AT_SPEED_10 * elapsedSeconds;
        for (Star star : stars) {
            star.z -= dz;
            if (star.z <= Z_NEAR) {
                respawn(star, false);
            }
        }
        repaint();
    }

    /**
     * Draw pass. Swing has already cleared/prepared the clip; we still fill the
     * background ourselves so a colour change in config is visible immediately.
     * {@code Graphics} is a throwaway context for this paint —
     * {@code create()}/{@code dispose()} keeps hints and strokes from leaking
     * into other components.
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            // Crisp squares, like the original VGA-era saver. Antialiasing would
            // soften 1×1 pixels into grey blobs and look “modern”, which we do not want.
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
            // scale is derived from the panel so the same 1/z math works in the
            // 600×200 settings preview and in exclusive full screen.
            double scale = FOCAL * Math.min(width, height);
            Color starColor = config.getStarColor();
            Color background = config.getBackgroundColor();
            boolean trails = config.isWarpTrails();
            int maxSize = config.getMaxStarSize();

            for (Star star : stars) {
                // Pinhole projection: dividing by z is why far stars sit near the
                // centre and near stars race to the edges. x and y never change
                // after spawn; only z does, in onFrame.
                double sx = cx + (star.x / star.z) * scale;
                double sy = cy + (star.y / star.z) * scale;

                if (offScreen(sx, sy, width, height, 8)) {
                    respawn(star, false);
                    continue;
                }

                double closeness = 1.0 - (star.z - Z_NEAR) / (Z_FAR - Z_NEAR);
                closeness = Math.clamp(closeness, 0.0, 1.0);
                int size = Math.max(1, (int) Math.round(1.0 + closeness * (maxSize - 1)));
                // Far stars stay dim so the field has depth; near stars approach starColor.
                Color color = fade(background, starColor, 0.22 + 0.78 * closeness);

                g2.setColor(color);
                if (trails && star.hasPrev) {
                    // No extra physics: the streak is last projected point → this one.
                    // Low warp: a couple of pixels (looks like a dot). High warp: a line.
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

    /**
     * Rebuild the pool only when the slider changes density. A growing
     * {@code ArrayList} would also work; a fixed array makes “count == length”
     * the whole contract.
     */
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

    /**
     * Recycle rather than allocate. {@code scatterDepth} is for the first fill
     * so the screen is not empty then a single wave; later respawns put the
     * star back on the far plane so it flies in again.
     */
    private void respawn(Star star, boolean scatterDepth) {
        star.x = random.nextDouble() * 2.0 - 1.0;
        star.y = random.nextDouble() * 2.0 - 1.0;
        if (scatterDepth) {
            star.z = Z_NEAR + random.nextDouble() * (Z_FAR - Z_NEAR);
        } else {
            star.z = Z_FAR - random.nextDouble() * 0.12;
        }
        // Drop the old trail so we do not draw a line from the previous life.
        star.hasPrev = false;
    }

    private static boolean offScreen(double sx, double sy, int width, int height, int margin) {
        return sx < -margin || sx > width + margin || sy < -margin || sy > height + margin;
    }

    /** Linear RGB blend. {@code t = 0} is {@code from} (background); {@code t = 1} is {@code to}. */
    private static Color fade(Color from, Color to, double t) {
        t = Math.clamp(t, 0.0, 1.0);
        int r = (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int g = (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        return new Color(r, g, b);
    }

    /**
     * One particle. {@code x,y,z} are world coordinates; {@code prevScreen*} are
     * last frame’s projected pixels, used only for warp trails.
     */
    private static final class Star {
        double x;
        double y;
        double z;
        double prevScreenX;
        double prevScreenY;
        boolean hasPrev;
    }
}
