package net.talaatharb.harness.ui;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.HostServices;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import net.talaatharb.harness.reply.MarkdownText;

public final class UiSupport {
    private static final Logger LOG = LoggerFactory.getLogger(UiSupport.class);
    private static volatile HostServices hostServices;

    private UiSupport() {
    }

    public static void setHostServices(HostServices services) {
        hostServices = services;
    }

    /** Asks before opening a link from model output; non-web links can only be copied. */
    static void confirmOpenLink(String link) {
        boolean openable = MarkdownText.isOpenableLink(link) && hostServices != null;
        ButtonType open = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        ButtonType copy = new ButtonType("Copy link", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, link, openable
                ? new ButtonType[] { open, copy, ButtonType.CANCEL } : new ButtonType[] { copy, ButtonType.CANCEL });
        alert.setTitle("Open link");
        alert.setHeaderText(openable ? "Open this link from the reply in your browser?"
                : "This link can't be opened from the chat. Copy it instead?");
        alert.showAndWait().ifPresent(choice -> {
            if (choice == open) {
                try {
                    hostServices.showDocument(link.strip());
                } catch (RuntimeException e) {
                    LOG.error("Could not open link {}", link, e);
                }
            } else if (choice == copy) {
                ChatBubbles.copyToClipboard(link);
            }
        });
    }

    public static void applyStyles(Scene scene) {
        scene.getStylesheets().add(Objects.requireNonNull(
                UiSupport.class.getResource("harness.css"), "harness.css").toExternalForm());
    }
}
