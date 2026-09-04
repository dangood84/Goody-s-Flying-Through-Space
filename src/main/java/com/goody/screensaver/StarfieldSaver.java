package com.goody.screensaver;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Process entry point. This class does not draw or store star coordinates;
 * it only chooses a launch path and hands a loaded {@link ScreensaverConfig}
 * to a window on the Swing Event Dispatch Thread (EDT).
 *
 * <p>{@code /s} or {@code --fullscreen} starts the saver immediately.
 * {@code /c} or {@code --config} opens the preferences dialog.
 * With no arguments, the settings dialog is shown.
 */
public final class StarfieldSaver {

    private StarfieldSaver() {
    }

    public static void main(String[] args) {
        LaunchMode mode = LaunchMode.fromArgs(args);
        // Windows Settings calls /p with a preview HWND. This Java UI cannot
        // parent into that native window, so we exit rather than flash a dialog.
        if (mode == LaunchMode.PREVIEW) {
            return;
        }

        // Swing components are not thread-safe. invokeLater posts this work onto
        // the EDT so window creation, painting, and later Timer ticks share one
        // thread. main() itself returns; the AWT thread keeps the JVM alive.
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            ScreensaverConfig config = ScreensaverConfig.load();
            switch (mode) {
                case FULLSCREEN -> new StarfieldFrame(config, () -> System.exit(0)).showFullScreen();
                case CONFIG, PREVIEW -> new SettingsDialog(config).setVisible(true);
            }
        });
    }

    private static void installLookAndFeel() {
        try {
            // Match the host OS widgets (macOS Aqua, Windows, etc.) instead of
            // the default Metal cross-platform theme.
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default cross-platform look if the system L&F is unavailable.
        }
    }

    /**
     * Last matching flag wins, so {@code app /c /s} runs full screen. Prefixes
     * {@code /}, {@code -}, and {@code --} are stripped so Windows .scr flags
     * and Unix-style flags share one parser.
     */
    enum LaunchMode {
        CONFIG,
        FULLSCREEN,
        PREVIEW;

        static LaunchMode fromArgs(String[] args) {
            LaunchMode mode = CONFIG;
            for (String raw : args) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String arg = stripPrefix(raw);
                if (arg.equalsIgnoreCase("s") || arg.equalsIgnoreCase("fullscreen")) {
                    mode = FULLSCREEN;
                } else if (startsWithIgnoreCase(arg, "c") || arg.equalsIgnoreCase("config")) {
                    // startsWith "c" also matches Windows /c:HWND (parent handle after the colon).
                    mode = CONFIG;
                } else if (startsWithIgnoreCase(arg, "p") || arg.equalsIgnoreCase("preview")) {
                    mode = PREVIEW;
                }
            }
            return mode;
        }

        private static String stripPrefix(String raw) {
            if (raw.startsWith("--")) {
                return raw.substring(2);
            }
            if (raw.startsWith("-") || raw.startsWith("/")) {
                return raw.substring(1);
            }
            return raw;
        }

        private static boolean startsWithIgnoreCase(String value, String prefix) {
            return value.regionMatches(true, 0, prefix, 0, prefix.length());
        }
    }
}
