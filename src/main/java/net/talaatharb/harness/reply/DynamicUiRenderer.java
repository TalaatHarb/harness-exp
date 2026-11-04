package net.talaatharb.harness.reply;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;

import java.util.List;

/** Turns {@link UiComponent} trees produced by the model into JavaFX nodes. */
public final class DynamicUiRenderer {
    static final int MAX_DEPTH = UiSchemaValidator.MAX_DEPTH;

    private DynamicUiRenderer() {
    }

    /**
     * @param actionSink receives a button's {@code action} text so it can be sent back to the agent
     */
    public static Node render(UiComponent c, Consumer<String> actionSink) {
        UiSchemaValidator.validate(c);
        return render(c, actionSink, 0, null);
    }

    private static Node render(UiComponent c, Consumer<String> actionSink, int depth, FormState form) {
        if (c == null || depth > MAX_DEPTH) {
            return new Label("[ui nesting too deep]");
        }
        String type = c.type() == null ? "" : c.type();
        switch (type) {
            case "form": {
                FormState state = new FormState();
                VBox box = new VBox(6);
                box.getStyleClass().add("ui-card");
                if (c.text() != null) {
                    box.getChildren().add(new Label(c.text()));
                }
                addChildren(box, c, actionSink, depth, state);
                Label errors = new Label();
                errors.setWrapText(true);
                errors.getStyleClass().add("notice");
                Button submit = new Button((String) c.props().getOrDefault("submitLabel", "Submit"));
                submit.setOnAction(event -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    List<String> problems = new ArrayList<>();
                    for (InputField field : state.fields) {
                        String value = field.value.get();
                        String error = FormSubmission.fieldError(field.type, value, field.required);
                        if (error != null) {
                            problems.add(field.name + ": " + error);
                        }
                        values.put(field.name, value);
                    }
                    errors.setText(String.join("\n", problems));
                    if (problems.isEmpty() && actionSink != null) {
                        actionSink.accept(FormSubmission.message(c.action(), values));
                    }
                });
                box.getChildren().addAll(errors, submit,
                        new Label("Sends these values to the assistant; does not make a booking."));
                return box;
            }
            case "input": {
                String name = (String) c.props().get("name");
                String inputType = (String) c.props().getOrDefault("inputType", "text");
                boolean required = Boolean.TRUE.equals(c.props().get("required"));
                String initial = (String) c.props().getOrDefault("value", "");
                String placeholder = (String) c.props().getOrDefault("placeholder", "");
                Node input;
                Supplier<String> value;
                if ("date".equals(inputType)) {
                    DatePicker date = new DatePicker();
                    date.getEditor().setPromptText(placeholder.isBlank() ? "YYYY-MM-DD" : placeholder);
                    date.setConverter(new javafx.util.StringConverter<>() {
                        public String toString(java.time.LocalDate date) {
                            return date == null ? "" : date.toString();
                        }
                        public java.time.LocalDate fromString(String text) {
                            try {
                                return text == null || text.isBlank() ? null : java.time.LocalDate.parse(text);
                            } catch (java.time.format.DateTimeParseException ex) {
                                return null;
                            }
                        }
                    });
                    date.valueProperty().addListener((obs, old, selected) -> {
                        if (selected != null) {
                            date.getEditor().setText(selected.toString());
                        }
                    });
                    if (!initial.isBlank()) {
                        date.setValue(java.time.LocalDate.parse(initial));
                    }
                    date.getEditor().setText(initial);
                    value = () -> date.getEditor().getText();
                    input = date;
                } else {
                    TextField field = new TextField(initial);
                    field.setPromptText(placeholder);
                    value = field::getText;
                    input = field;
                }
                input.setId("form-input-" + name);
                Label label = new Label((c.text() == null ? name : c.text()) + (required ? " *" : ""));
                label.setLabelFor(input);
                form.fields.add(new InputField(name, inputType, required, value));
                return new VBox(3, label, input);
            }
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
                addChildren(box, c, actionSink, depth, form);
                return box;
            }
            case "row": {
                HBox box = new HBox(6);
                addChildren(box, c, actionSink, depth, form);
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

    private static void addChildren(Pane box, UiComponent c, Consumer<String> actionSink, int depth, FormState form) {
        for (UiComponent child : c.children()) {
            box.getChildren().add(render(child, actionSink, depth + 1, form));
        }
    }

    private static final class FormState {
        final List<InputField> fields = new ArrayList<>();
    }

    private record InputField(String name, String type, boolean required, Supplier<String> value) {
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
