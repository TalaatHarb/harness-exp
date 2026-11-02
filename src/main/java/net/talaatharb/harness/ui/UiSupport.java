package net.talaatharb.harness.ui;

import java.util.Objects;

import javafx.scene.Scene;

public final class UiSupport {
    private UiSupport() {
    }

    public static void applyStyles(Scene scene) {
        scene.getStylesheets().add(Objects.requireNonNull(
                UiSupport.class.getResource("harness.css"), "harness.css").toExternalForm());
    }
}
