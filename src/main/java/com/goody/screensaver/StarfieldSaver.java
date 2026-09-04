package com.goody.screensaver;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point for Goody's Flying Through Space screensaver.
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

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default cross-platform look if the system L&F is unavailable.
        }
    }

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
