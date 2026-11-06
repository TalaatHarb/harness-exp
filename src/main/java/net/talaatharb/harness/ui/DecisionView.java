package net.talaatharb.harness.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import net.talaatharb.harness.interaction.DecisionRequest;
import net.talaatharb.harness.interaction.DecisionResponse;

/**
 * Content of the "ask the user" window. Three layouts: two big side-by-side buttons for a binary choice, a single
 * labelled text area when only input is wanted, and a radio list with an optional input otherwise.
 * {@code onAnswer} is called once with the answer.
 */
final class DecisionView {
    private DecisionView() {
    }

    static VBox build(DecisionRequest request, Consumer<DecisionResponse> onAnswer) {
        VBox root = new VBox(14);
        root.setId("decisionView");
        root.setPadding(new Insets(18));
        root.setMinWidth(380);
        root.setPrefWidth(460);
        Label question = new Label(request.question());
        question.setWrapText(true);
        question.setId("decisionQuestion");
        question.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        root.getChildren().add(question);
        switch (request.mode()) {
            case BINARY -> root.getChildren().add(binary(request, onAnswer));
            case INPUT_ONLY -> root.getChildren().addAll(inputOnly(request, onAnswer));
            case CHOICES -> root.getChildren().addAll(choices(request, onAnswer));
        }
        return root;
    }

    private static Node binary(DecisionRequest request, Consumer<DecisionResponse> onAnswer) {
        HBox row = new HBox(12);
        row.setId("decisionBinary");
        row.setAlignment(Pos.CENTER);
        for (int i = 0; i < 2; i++) {
            String option = request.options().get(i);
            Button button = new Button(option);
            button.setId("decisionOption" + i);
            button.setWrapText(true);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinHeight(52);
            button.setStyle("-fx-font-size: 14px;");
            if (i == 0) {
                button.setDefaultButton(true);
            }
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(e -> onAnswer.accept(new DecisionResponse(option, null)));
            row.getChildren().add(button);
        }
        return row;
    }

    private static Node[] inputOnly(DecisionRequest request, Consumer<DecisionResponse> onAnswer) {
        Label label = new Label(request.inputLabel());
        label.setWrapText(true);
        TextArea input = new TextArea();
        input.setId("decisionInput");
        input.setWrapText(true);
        input.setPrefRowCount(4);
        label.setLabelFor(input);
        Button submit = new Button("Submit");
        submit.setId("decisionSubmit");
        submit.setDefaultButton(true);
        submit.setOnAction(e -> onAnswer.accept(new DecisionResponse(null, input.getText())));
        return new Node[] { label, input, buttons(submit) };
    }

    private static Node[] choices(DecisionRequest request, Consumer<DecisionResponse> onAnswer) {
        ToggleGroup group = new ToggleGroup();
        VBox list = new VBox(8);
        list.setId("decisionChoices");
        for (String option : request.options()) {
            RadioButton radio = new RadioButton(option);
            radio.setWrapText(true);
            radio.setToggleGroup(group);
            radio.setUserData(option);
            list.getChildren().add(radio);
        }
        group.selectToggle(group.getToggles().get(0));
        Label label = null;
        TextField input = null;
        if (request.hasInput()) {
            label = new Label(request.inputLabel());
            label.setWrapText(true);
            input = new TextField();
            input.setId("decisionInput");
            label.setLabelFor(input);
        }
        TextField inputField = input;
        Button submit = new Button("Submit");
        submit.setId("decisionSubmit");
        submit.setDefaultButton(true);
        submit.setOnAction(e -> onAnswer.accept(new DecisionResponse(
                String.valueOf(group.getSelectedToggle().getUserData()),
                inputField == null ? null : inputField.getText())));
        return label == null ? new Node[] { list, buttons(submit) }
                : new Node[] { list, label, input, buttons(submit) };
    }

    private static HBox buttons(Button submit) {
        HBox box = new HBox(submit);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }
}
