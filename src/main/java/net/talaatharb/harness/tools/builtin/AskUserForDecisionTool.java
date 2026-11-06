package net.talaatharb.harness.tools.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;

import net.talaatharb.harness.interaction.DecisionRequest;
import net.talaatharb.harness.interaction.DecisionResponse;
import net.talaatharb.harness.interaction.UserPrompter;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Asks the user to choose between options and/or type an answer in a dedicated window. Safe: it changes nothing. */
public class AskUserForDecisionTool implements Tool {
    static final int MAX_OPTIONS = 20;
    static final int MAX_TEXT = 2_000;
    static final long TIMEOUT_MINUTES = 30;

    private final UserPrompter prompter;

    @Inject
    public AskUserForDecisionTool(UserPrompter prompter) {
        this.prompter = prompter;
    }

    @Override
    public String name() {
        return "ask_user_for_decision";
    }

    @Override
    public String description() {
        return "Asks the user a question in a pop-up window and returns the answer. Provide 'options' to let the user "
                + "pick one (exactly two options without an input shows a yes/no style window), and/or 'inputLabel' to "
                + "show a text field with that label. With neither, a plain text answer is requested. Use it when you "
                + "need a decision or missing information instead of guessing.";
    }

    @Override
    public String argumentsHint() {
        return "{\"question\": \"text\", \"options\": [\"A\", \"B\"], \"inputLabel\": \"optional label for a text field\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws InterruptedException {
        Object q = arguments.get("question");
        if (!(q instanceof String question) || question.isBlank()) {
            return ToolResult.error("Missing required argument 'question'");
        }
        List<String> options = new ArrayList<>();
        Object rawOptions = arguments.get("options");
        if (rawOptions != null) {
            if (!(rawOptions instanceof List<?> list)) {
                return ToolResult.error("'options' must be an array of strings");
            }
            for (Object o : list) {
                String s = o == null ? "" : o.toString().strip();
                if (!s.isEmpty() && !options.contains(s)) {
                    options.add(s);
                }
            }
        }
        if (options.size() > MAX_OPTIONS) {
            return ToolResult.error("At most " + MAX_OPTIONS + " options are supported");
        }
        Object rawLabel = arguments.get("inputLabel");
        String label = rawLabel instanceof String s && !s.isBlank() ? s.strip() : null;
        if (options.isEmpty() && label == null) {
            label = "Your answer";
        }
        if (question.length() > MAX_TEXT || options.stream().anyMatch(o -> o.length() > MAX_TEXT)
                || label != null && label.length() > MAX_TEXT) {
            return ToolResult.error("Question, options and label are limited to " + MAX_TEXT + " characters");
        }

        CompletableFuture<DecisionResponse> pending = prompter.ask(new DecisionRequest(question.strip(), options, label));
        DecisionResponse response;
        try {
            response = pending.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return ToolResult.error("The user did not answer in time.");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException | CancellationException e) {
            return ToolResult.error("Could not ask the user: " + e.getMessage());
        }
        if (response == null) {
            return ToolResult.error("The user dismissed the question without answering.");
        }
        StringBuilder out = new StringBuilder();
        if (response.choice() != null) {
            out.append("Chosen option: ").append(response.choice());
        }
        if (response.input() != null) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(label == null ? "Input" : label).append(": ").append(response.input());
        }
        return ToolResult.ok(out.isEmpty() ? "(empty answer)" : out.toString());
    }
}
