package com.goody.screensaver;

/**
 * Compatibility {@code main} so project-style run commands still work. All
 * behaviour lives in {@link StarfieldSaver}; this class only forwards argv.
 */
public final class FlyingThroughSpace {

    private FlyingThroughSpace() {
    }

    public static void main(String[] args) {
        StarfieldSaver.main(args);
    }
}
