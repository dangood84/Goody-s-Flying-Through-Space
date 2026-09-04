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
 * Double-buffered panel that paints a classic “flying through space” starfield
 * with {@link Graphics2D}. Stars live in a simple 3D volume, are perspective-
 * projected onto the screen, and grow as they approach the viewer — the same
 * idea as the Windows 3.1 / 95 Starfield Simulation screensaver, without a
 * real 3D pipeline.
 *
 * <p>Animation is driven by {@link Timer} at ~60 FPS, with movement based on
 * elapsed time so warp speed stays steady if a tick is delayed.
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
    /** Warp speed 1…20 mapped to z-units per second. */
    private static final double Z_PER_SECOND_AT_SPEED_10 = 1.0;

    private final ScreensaverConfig config;
    private final Timer timer;
    private final Random random = new Random();

    private Star[] stars = new Star[0];
    private long lastNanos;

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

    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    @Override
    public void removeNotify() {
        stop();
        super.removeNotify();
    }

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

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            // Crisp squares, like the original VGA-era saver — no antialiasing.
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

    private static boolean offScreen(double sx, double sy, int width, int height, int margin) {
        return sx < -margin || sx > width + margin || sy < -margin || sy > height + margin;
    }

    private static Color fade(Color from, Color to, double t) {
        t = Math.clamp(t, 0.0, 1.0);
        int r = (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int g = (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        return new Color(r, g, b);
    }

    private static final class Star {
        double x;
        double y;
        double z;
        double prevScreenX;
        double prevScreenY;
        boolean hasPrev;
    }
}
