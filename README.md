# Goody's Flying Through Space

A full-screen Java Swing screensaver in the spirit of the Windows 3.1 / 95 / 98 **Starfield Simulation** (also called Flying Through Space): stars rush out from the centre of a black screen, grow as they approach, and streak at higher warp speeds.

It is deliberately simple — double-buffered `JPanel` and `Graphics2D` at about 60 FPS, not a 3D engine. Preferences (star count, warp speed, size, trails, colors) are saved with `java.util.prefs.Preferences` and restored on the next launch.

## OS-native ports

This Java app is the cross-platform reference. Native installers live in **sibling folders** (not inside this repo):

| Folder | Target | What it actually installs |
|--------|--------|---------------------------|
| `../GoodysFlyingThroughSpaceV2Mac` | macOS Sonoma+ | Native `.saver` for System Settings (no paid Apple Developer ID needed on the Mac that builds it) |
| `../GoodysFlyingThroughSpaceV2Win` | Windows 10+ | `.scr` launcher around the Java jar |
| `../GoodysFlyingThroughSpaceV2Lin` | Linux / Raspberry Pi OS | `.desktop` + xscreensaver hook around the Java jar |

A JAR cannot appear in macOS System Settings or as a Windows `.scr` by itself. See each folder’s README.

## Requirements

- **Java 21** or later (`java` and `javac` on your `PATH`)

## Run

From the project root:

```bash
./run.sh
```

That compiles to `out/` and opens the settings dialog. You can also pass flags through:

```bash
./run.sh --config
./run.sh --fullscreen
./run.sh /s
```

Or with Make:

```bash
make config        # settings dialog
make screensaver   # full-screen saver
make clean         # remove compiled classes
```

Manual compile and run:

```bash
javac --release 21 -encoding UTF-8 -d out src/main/java/com/goody/screensaver/*.java
java -cp out com.goody.screensaver.StarfieldSaver --config
```

## Command-line flags

| Flag | Action |
|------|--------|
| *(none)*, `/c`, `--config` | Open the preferences dialog |
| `/s`, `--fullscreen` | Start the full-screen screensaver immediately |
| `/p`, `--preview` | No-op (Windows-style preview hook); exits without a window |

Settings from the dialog are persisted, so `--fullscreen` uses the last saved look.

## Using it

1. Set the number of stars, warp speed, maximum star size, warp trails, and colors.
2. Click **Start screensaver** (or launch with `--fullscreen`).
3. Press any key, or move the mouse more than a few pixels, to exit.

The live preview in the dialog uses the same starfield renderer as full screen.

Defaults match the classic saver: white stars on black, trails on, a moderate warp.

## Project layout

```
src/main/java/com/goody/screensaver/
  StarfieldSaver.java      # main, flag parsing
  FlyingThroughSpace.java  # compatibility entry point
  SettingsDialog.java      # JDialog preferences UI
  StarfieldFrame.java      # full-screen window and wake-on-input
  StarfieldPanel.java      # Timer + perspective starfield
  ScreensaverConfig.java   # settings + Preferences load/save
```
