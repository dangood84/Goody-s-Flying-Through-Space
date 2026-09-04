package com.goody.screensaver;

import java.awt.Color;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Mutable appearance/speed model. This is not window state (each star's
 * {@code x,y,z} lives on {@link StarfieldPanel}). {@link Preferences} writes
 * to the OS user store (Windows registry / macOS defaults / Linux file under
 * the home directory), not a file in the project, so values survive relaunch.
 */
public final class ScreensaverConfig {

    private static final Logger LOG = Logger.getLogger(ScreensaverConfig.class.getName());
    private static final Preferences PREFS = Preferences.userNodeForPackage(ScreensaverConfig.class);

    private static final String KEY_STAR_COUNT = "starCount";
    private static final String KEY_WARP_SPEED = "warpSpeed";
    private static final String KEY_MAX_STAR_SIZE = "maxStarSize";
    private static final String KEY_WARP_TRAILS = "warpTrails";
    private static final String KEY_STAR_COLOR = "starColor";
    private static final String KEY_BACKGROUND_COLOR = "backgroundColor";

    private static final int DEFAULT_STAR_COUNT = 250;
    private static final int DEFAULT_WARP_SPEED = 8;
    private static final int DEFAULT_MAX_STAR_SIZE = 4;
    private static final boolean DEFAULT_WARP_TRAILS = true;
    private static final int DEFAULT_STAR_COLOR = Color.WHITE.getRGB();
    private static final int DEFAULT_BACKGROUND_COLOR = Color.BLACK.getRGB();

    private int starCount = DEFAULT_STAR_COUNT;
    private int warpSpeed = DEFAULT_WARP_SPEED;
    private int maxStarSize = DEFAULT_MAX_STAR_SIZE;
    private boolean warpTrails = DEFAULT_WARP_TRAILS;
    private Color starColor = new Color(DEFAULT_STAR_COLOR, true);
    private Color backgroundColor = new Color(DEFAULT_BACKGROUND_COLOR, true);

    /**
     * Overlay stored keys onto a fresh instance. Missing keys keep the field
     * initializers (defaults), which is why a first-ever launch still looks designed.
     */
    public static ScreensaverConfig load() {
        var config = new ScreensaverConfig();
        config.starCount = PREFS.getInt(KEY_STAR_COUNT, config.starCount);
        config.warpSpeed = PREFS.getInt(KEY_WARP_SPEED, config.warpSpeed);
        config.maxStarSize = PREFS.getInt(KEY_MAX_STAR_SIZE, config.maxStarSize);
        config.warpTrails = PREFS.getBoolean(KEY_WARP_TRAILS, config.warpTrails);
        // Packed ARGB int is what Preferences can store; the true alpha constructor
        // preserves the high bits if we ever persist translucent colours.
        config.starColor = new Color(PREFS.getInt(KEY_STAR_COLOR, config.starColor.getRGB()), true);
        config.backgroundColor = new Color(PREFS.getInt(KEY_BACKGROUND_COLOR, config.backgroundColor.getRGB()), true);
        // Re-run setters so a hand-edited prefs store cannot inject out-of-range values.
        config.setStarCount(config.starCount);
        config.setWarpSpeed(config.warpSpeed);
        config.setMaxStarSize(config.maxStarSize);
        return config;
    }

    public void save() {
        PREFS.putInt(KEY_STAR_COUNT, starCount);
        PREFS.putInt(KEY_WARP_SPEED, warpSpeed);
        PREFS.putInt(KEY_MAX_STAR_SIZE, maxStarSize);
        PREFS.putBoolean(KEY_WARP_TRAILS, warpTrails);
        PREFS.putInt(KEY_STAR_COLOR, starColor.getRGB());
        PREFS.putInt(KEY_BACKGROUND_COLOR, backgroundColor.getRGB());
        try {
            // flush() forces the OS store now; without it, a kill -9 could drop the last edit.
            PREFS.flush();
        } catch (BackingStoreException ex) {
            LOG.log(Level.WARNING, "Unable to persist screensaver preferences", ex);
        }
    }

    /** Snapshot so full-screen is not sharing a live object the dialog might still mutate. */
    public ScreensaverConfig copy() {
        var copy = new ScreensaverConfig();
        copy.starCount = starCount;
        copy.warpSpeed = warpSpeed;
        copy.maxStarSize = maxStarSize;
        copy.warpTrails = warpTrails;
        copy.starColor = starColor;
        copy.backgroundColor = backgroundColor;
        return copy;
    }

    public int getStarCount() {
        return starCount;
    }

    public void setStarCount(int starCount) {
        this.starCount = Math.clamp(starCount, 20, 800);
    }

    public int getWarpSpeed() {
        return warpSpeed;
    }

    public void setWarpSpeed(int warpSpeed) {
        this.warpSpeed = Math.clamp(warpSpeed, 1, 20);
    }

    public int getMaxStarSize() {
        return maxStarSize;
    }

    public void setMaxStarSize(int maxStarSize) {
        this.maxStarSize = Math.clamp(maxStarSize, 1, 8);
    }

    public boolean isWarpTrails() {
        return warpTrails;
    }

    public void setWarpTrails(boolean warpTrails) {
        this.warpTrails = warpTrails;
    }

    public Color getStarColor() {
        return starColor;
    }

    public void setStarColor(Color starColor) {
        this.starColor = starColor == null ? Color.WHITE : starColor;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor == null ? Color.BLACK : backgroundColor;
    }
}
