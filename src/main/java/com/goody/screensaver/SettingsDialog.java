package com.goody.screensaver;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

/**
 * Settings view bound to one {@link ScreensaverConfig}. Controls write the model
 * immediately; the preview {@link StarfieldPanel} rereads that model every tick
 * and paint, so there is no separate “Apply” step for the live starfield.
 */
public final class SettingsDialog extends JDialog {

    private final ScreensaverConfig config;
    /**
     * dispose() always fires windowClosed. When we leave for full screen we must
     * not System.exit, or the saver never appears.
     */
    private boolean launchingScreensaver;

    public SettingsDialog(ScreensaverConfig config) {
        // null owner: this is the primary window, not a child of another Frame.
        super((Frame) null, "Goody's Flying Through Space", false);
        this.config = config;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(640, 560));

        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildForm(), BorderLayout.CENTER);
        root.add(buildPreviewAndActions(), BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (!launchingScreensaver) {
                    config.save();
                    System.exit(0);
                }
            }
        });
    }

    private JPanel buildHeader() {
        var header = new JPanel(new BorderLayout());
        var title = new JLabel("Preferences & Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        var subtitle = new JLabel("Saved automatically  •  /c config  •  /s fullscreen  •  Java 21");
        subtitle.setForeground(new Color(90, 90, 90));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildForm() {
        var form = new JPanel(new GridBagLayout());
        var constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 4, 6, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;

        // ChangeListener fires while the thumb is dragged, not only on release, so
        // the preview rebuilds density / warp as the tester slides — that is intentional.
        addRow(form, constraints, 0, "Number of stars", sliderRow(
                20,
                800,
                100,
                20,
                config.getStarCount(),
                SettingsDialog::starCountLabel,
                value -> persist(() -> config.setStarCount(value))));

        addRow(form, constraints, 1, "Warp speed", sliderRow(
                1,
                20,
                5,
                1,
                config.getWarpSpeed(),
                SettingsDialog::warpSpeedLabel,
                value -> persist(() -> config.setWarpSpeed(value))));

        addRow(form, constraints, 2, "Star size", sliderRow(
                1,
                8,
                1,
                1,
                config.getMaxStarSize(),
                SettingsDialog::starSizeLabel,
                value -> persist(() -> config.setMaxStarSize(value))));

        var trailsBox = new JCheckBox("Warp trails (streaks at speed)", config.isWarpTrails());
        trailsBox.addItemListener(event -> persist(() -> config.setWarpTrails(trailsBox.isSelected())));
        addRow(form, constraints, 3, "Motion", trailsBox);

        addRow(form, constraints, 4, "Star color", colorRow(config.getStarColor(), color -> persist(() -> config.setStarColor(color))));
        addRow(form, constraints, 5, "Background", colorRow(config.getBackgroundColor(), color -> persist(() -> config.setBackgroundColor(color))));

        return form;
    }

    private JPanel buildPreviewAndActions() {
        var south = new JPanel(new BorderLayout(0, 10));

        // Same class as full screen, same config instance: slider changes are visible
        // on the next timer paint without wiring a custom listener into StarfieldPanel.
        var preview = new StarfieldPanel(config);
        preview.setPreferredSize(new Dimension(600, 200));
        preview.setBorder(BorderFactory.createLineBorder(new Color(40, 40, 40)));
        south.add(preview, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var close = new JButton("Close");
        var start = new JButton("Start screensaver");
        start.addActionListener(event -> launchScreensaver());
        close.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(start);
        buttons.add(close);
        buttons.add(start);
        south.add(buttons, BorderLayout.SOUTH);

        var hint = new JLabel("Any key or a significant mouse move exits the full-screen saver.");
        hint.setForeground(new Color(90, 90, 90));
        south.add(hint, BorderLayout.NORTH);
        return south;
    }

    private void launchScreensaver() {
        config.save();
        launchingScreensaver = true;
        setVisible(false);
        dispose();
        new StarfieldFrame(config.copy(), () -> System.exit(0)).showFullScreen();
    }

    /** Mutate the in-memory model, then flush Preferences so a crash still keeps the last edit. */
    private void persist(Runnable update) {
        update.run();
        config.save();
    }

    private JPanel colorRow(Color initial, Consumer<Color> onChoose) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        var swatch = new JPanel();
        swatch.setPreferredSize(new Dimension(36, 22));
        swatch.setBackground(initial);
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        var choose = new JButton("Choose...");
        choose.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(this, "Choose color", swatch.getBackground());
            if (chosen != null) {
                swatch.setBackground(chosen);
                onChoose.accept(chosen);
            }
        });
        row.add(swatch);
        row.add(choose);
        return row;
    }

    private JPanel sliderRow(
            int min,
            int max,
            int majorTick,
            int minorTick,
            int initial,
            IntFunction<String> labelFor,
            Consumer<Integer> onChange) {
        var slider = new JSlider(min, max, initial);
        slider.setMajorTickSpacing(majorTick);
        slider.setMinorTickSpacing(minorTick);
        slider.setPaintTicks(true);
        var value = new JLabel(labelFor.apply(initial), JLabel.RIGHT);
        value.setPreferredSize(new Dimension(88, 16));
        slider.addChangeListener(event -> {
            onChange.accept(slider.getValue());
            value.setText(labelFor.apply(slider.getValue()));
        });
        var row = new JPanel(new BorderLayout(8, 0));
        row.add(slider, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private static void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, constraints);
    }

    private static String starCountLabel(int count) {
        return count + " stars";
    }

    private static String warpSpeedLabel(int speed) {
        return speed + " / 20";
    }

    private static String starSizeLabel(int size) {
        return size + " px";
    }
}
