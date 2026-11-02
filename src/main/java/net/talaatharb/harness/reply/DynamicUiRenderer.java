package net.talaatharb.harness.reply;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;

import java.util.List;

/** Turns {@link UiComponent} trees produced by the model into JavaFX nodes. */
public final class DynamicUiRenderer {
    static final int MAX_DEPTH = 8;

    private DynamicUiRenderer() {
    }

    /**
     * @param actionSink receives a button's {@code action} text so it can be sent back to the agent
     */
    public static Node render(UiComponent c, Consumer<String> actionSink) {
        return render(c, actionSink, 0);
    }

    private static Node render(UiComponent c, Consumer<String> actionSink, int depth) {
        if (c == null || depth > MAX_DEPTH) {
            return new Label("[ui nesting too deep]");
        }
        String type = c.type() == null ? "" : c.type();
        switch (type) {
            case "column":
            case "card": {
                VBox box = new VBox(6);
                if ("card".equals(type)) {
                    box.getStyleClass().add("ui-card");
                    box.setPadding(new Insets(10));
                    if (c.text() != null) {
                        Label title = new Label(c.text());
                        title.getStyleClass().add("ui-heading");
                        box.getChildren().add(title);
                    }
                }
                addChildren(box, c, actionSink, depth);
                return box;
            }
            case "row": {
                HBox box = new HBox(6);
                addChildren(box, c, actionSink, depth);
                return box;
            }
            case "heading": {
                Label l = new Label(c.text());
                l.getStyleClass().add("ui-heading");
                l.setWrapText(true);
                return l;
            }
            case "badge": {
                Label l = new Label(c.text());
                l.getStyleClass().add("ui-badge");
                return l;
            }
            case "button": {
                Button b = new Button(c.text() == null ? "OK" : c.text());
                String action = c.action() != null ? c.action() : c.text();
                b.setOnAction(e -> {
                    if (actionSink != null && action != null) {
                        actionSink.accept(action);
                    }
                });
                return b;
            }
            case "list": {
                ListView<String> list = new ListView<>(FXCollections.observableArrayList(c.items()));
                list.setPrefHeight(Math.min(8, Math.max(1, c.items().size())) * 26 + 4);
                return list;
            }
            case "table":
                return table(c);
            case "progress": {
                ProgressBar bar = new ProgressBar(c.value() == null ? -1 : Math.max(0, Math.min(1, c.value())));
                bar.setMaxWidth(Double.MAX_VALUE);
                return c.text() == null ? bar : new VBox(2, new Label(c.text()), bar);
            }
            case "separator":
                return new Separator();
            case "text": {
                Label l = new Label(c.text());
                l.setWrapText(true);
                return l;
            }
            default: {
                Label l = new Label("[unsupported ui component: " + type + "] " + (c.text() == null ? "" : c.text()));
                l.setWrapText(true);
                return l;
            }
        }
    }

    private static void addChildren(Pane box, UiComponent c, Consumer<String> actionSink, int depth) {
        for (UiComponent child : c.children()) {
            box.getChildren().add(render(child, actionSink, depth + 1));
        }
    }

    private static Node table(UiComponent c) {
        TableView<List<String>> table = new TableView<>();
        for (int i = 0; i < c.columns().size(); i++) {
            final int index = i;
            TableColumn<List<String>, String> col = new TableColumn<>(c.columns().get(i));
            col.setCellValueFactory(row -> new ReadOnlyStringWrapper(
                    index < row.getValue().size() ? row.getValue().get(index) : ""));
            table.getColumns().add(col);
        }
        table.setItems(FXCollections.observableArrayList(c.rows()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(Math.min(10, c.rows().size() + 1) * 26 + 8);
        return table;
    }
}
