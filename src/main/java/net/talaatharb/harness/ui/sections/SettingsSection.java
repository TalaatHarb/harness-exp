package net.talaatharb.harness.ui.sections;

import java.util.function.Supplier;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import net.talaatharb.harness.config.HarnessSettings;

/** One tab of the harness configuration window. */
public interface SettingsSection {

    /** Populate controls from the draft settings. */
    void load(HarnessSettings settings);

    /** Write control values back into the draft settings. */
    void apply(HarnessSettings settings);

    /** Supplies a copy of the draft with all sections applied (for previews). */
    default void setDraftSource(Supplier<HarnessSettings> source) {
    }

    static void intSpinner(Spinner<Integer> spinner, int min, int max, int value) {
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max,
                Math.max(min, Math.min(max, value))));
        spinner.setEditable(true);
    }

    static int spinnerValue(Spinner<Integer> spinner) {
        try {
            spinner.increment(0);
        } catch (RuntimeException ignored) {
            // invalid text: keep the last committed value
        }
        return spinner.getValue();
    }
}
