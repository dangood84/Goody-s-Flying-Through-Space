package com.goody.screensaver;

import java.awt.Cursor;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;

/**
 * Full-screen window shell. Owns exclusive display mode and wake-on-input;
 * the starfield itself is delegated to {@link StarfieldPanel}.
 */
public final class StarfieldFrame extends JFrame {

    private static final int MOUSE_MOVE_EXIT_PIXELS = 12;

    private final GraphicsDevice device;
    private final Runnable onExit;
    /** Guard so key + motion cannot run teardown twice (second pass would NPE or re-exit). */
    private boolean exited;
    private Point firstMousePoint;

    public StarfieldFrame(ScreensaverConfig config, Runnable onExit) {
        super("Goody's Flying Through Space");
        this.onExit = onExit;
        this.device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

        // Undecorated + exclusive full screen hides the title bar and typically the menu bar/dock.
        setUndecorated(true);
        setResizable(false);
        setAlwaysOnTop(true);
        // We handle dismiss ourselves; the OS close button is gone anyway.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setCursor(invisibleCursor());
        setFocusable(true);

        StarfieldPanel panel = new StarfieldPanel(config);
        setContentPane(panel);
        bindWakeListeners(panel);
    }

    public void showFullScreen() {
        if (device.isFullScreenSupported()) {
            // Exclusive mode: the device shows only this window. Must later pass null
            // to restore the desktop, or the user can be stuck without a menu bar.
            device.setFullScreenWindow(this);
        } else {
            setExtendedState(MAXIMIZED_BOTH);
            setVisible(true);
        }
        toFront();
        requestFocus();
        getContentPane().requestFocusInWindow();
    }

    private void bindWakeListeners(StarfieldPanel panel) {
        KeyAdapter keys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                exitScreensaver();
            }

            @Override
            public void keyTyped(KeyEvent event) {
                exitScreensaver();
            }
        };
        // Listen on both frame and panel: depending on OS/focus, key events may hit either.
        addKeyListener(keys);
        panel.addKeyListener(keys);

        MouseMotionAdapter mouse = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                onMouseMoved(event.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                onMouseMoved(event.getPoint());
            }
        };
        addMouseMotionListener(mouse);
        panel.addMouseMotionListener(mouse);
    }

    private void onMouseMoved(Point point) {
        if (firstMousePoint == null) {
            // Entering full screen often synthesizes a motion event. Ignore the first sample
            // so we do not quit before the user actually moved.
            firstMousePoint = point;
            return;
        }
        int dx = Math.abs(point.x - firstMousePoint.x);
        int dy = Math.abs(point.y - firstMousePoint.y);
        if (dx >= MOUSE_MOVE_EXIT_PIXELS || dy >= MOUSE_MOVE_EXIT_PIXELS) {
            exitScreensaver();
        }
    }

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

    private static Cursor invisibleCursor() {
        // A 1×1 empty image is how AWT hides the pointer; there is no setVisible(false) on Cursor.
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        return Toolkit.getDefaultToolkit().createCustomCursor(image, new Point(0, 0), "hidden");
    }
}
