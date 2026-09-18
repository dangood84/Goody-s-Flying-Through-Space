package com.goody.screensaver;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;

/**
 * Window shell around {@link StarfieldPanel}. Full-screen mode owns exclusive
 * display and wake-on-input (classic screensaver). Windowed mode is a normal
 * decorated, resizable frame — standalone Java app only, not the OS ports.
 */
public final class StarfieldFrame extends JFrame {

    private static final int MOUSE_MOVE_EXIT_PIXELS = 12;
    private static final int WINDOWED_WIDTH = 960;
    private static final int WINDOWED_HEIGHT = 600;

    private final GraphicsDevice device;
    private final Runnable onExit;
    private final boolean windowed;
    /** Guard so key + motion cannot run teardown twice (second pass would NPE or re-exit). */
    private boolean exited;
    private Point firstMousePoint;

    public StarfieldFrame(ScreensaverConfig config, Runnable onExit) {
        this(config, onExit, false);
    }

    public StarfieldFrame(ScreensaverConfig config, Runnable onExit, boolean windowed) {
        super("Goody's Flying Through Space");
        this.onExit = onExit;
        this.windowed = windowed;
        this.device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

        if (windowed) {
            setResizable(true);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(400, 300));
        } else {
            // Undecorated + exclusive full screen hides the title bar and typically the menu bar/dock.
            setUndecorated(true);
            setResizable(false);
            setAlwaysOnTop(true);
            // We handle dismiss ourselves; the OS close button is gone anyway.
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setCursor(invisibleCursor());
        }
        setFocusable(true);

        StarfieldPanel panel = new StarfieldPanel(config);
        setContentPane(panel);
        bindWakeListeners(panel);
        if (windowed) {
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    exitScreensaver();
                }
            });
        }
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

    /**
     * Ordinary decorated window. Projection in {@link StarfieldPanel} uses the
     * panel size, so resizing the frame recentres the vanishing point.
     */
    public void showWindowed() {
        setSize(WINDOWED_WIDTH, WINDOWED_HEIGHT);
        setLocationRelativeTo(null);
        setVisible(true);
        toFront();
        requestFocus();
        getContentPane().requestFocusInWindow();
    }

    private void bindWakeListeners(StarfieldPanel panel) {
        KeyAdapter keys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                onKey(event);
            }

            @Override
            public void keyTyped(KeyEvent event) {
                onKey(event);
            }
        };
        // Listen on both frame and panel: depending on OS/focus, key events may hit either.
        addKeyListener(keys);
        panel.addKeyListener(keys);

        if (windowed) {
            return;
        }

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

    private void onKey(KeyEvent event) {
        if (windowed) {
            // Title-bar close is the usual exit; Escape matches typical windowed-app habit.
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE || event.getKeyChar() == KeyEvent.VK_ESCAPE) {
                exitScreensaver();
            }
            return;
        }
        exitScreensaver();
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
