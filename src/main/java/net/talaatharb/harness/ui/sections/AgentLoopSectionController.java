package net.talaatharb.harness.ui.sections;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import net.talaatharb.harness.agent.AgentLoopLimits;
import net.talaatharb.harness.agent.HookPoint;
import net.talaatharb.harness.agent.HookRegistry;
import net.talaatharb.harness.config.HarnessSettings;

public class AgentLoopSectionController implements SettingsSection {
    private final HookRegistry hooks;

    @FXML private Spinner<Integer> iterationsSpinner;
    @FXML private Spinner<Integer> toolCallsSpinner;
    @FXML private Spinner<Integer> durationSpinner;
    @FXML private Spinner<Integer> identicalSpinner;
    @FXML private Spinner<Integer> errorsSpinner;
    @FXML private Label iterationsMax;
    @FXML private Label toolCallsMax;
    @FXML private Label durationMax;
    @FXML private Label identicalMax;
    @FXML private Label errorsMax;
    @FXML private TextArea hooksArea;

    @Inject
    public AgentLoopSectionController(HookRegistry hooks) {
        this.hooks = hooks;
    }

    @FXML
    private void initialize() {
        iterationsMax.setText("hard max " + AgentLoopLimits.HARD_MAX_ITERATIONS);
        toolCallsMax.setText("hard max " + AgentLoopLimits.HARD_MAX_TOOL_CALLS);
        durationMax.setText("hard max " + AgentLoopLimits.HARD_MAX_DURATION.toSeconds());
        identicalMax.setText("hard max " + AgentLoopLimits.HARD_MAX_IDENTICAL_TOOL_CALLS);
        errorsMax.setText("hard max " + AgentLoopLimits.HARD_MAX_CONSECUTIVE_ERRORS);
        StringBuilder sb = new StringBuilder(hooks.size() + " hook(s) registered. Bind AgentHook implementations in "
                + "AppModule or call HookRegistry.register(...) at runtime. Hooks may call context.abort(reason).\n\n");
        for (HookPoint p : HookPoint.values()) {
            sb.append("• ").append(p.name()).append('\n');
        }
        hooksArea.setText(sb.toString());
    }

    @Override
    public void load(HarnessSettings s) {
        SettingsSection.intSpinner(iterationsSpinner, 1, AgentLoopLimits.HARD_MAX_ITERATIONS, s.agentLoop.maxIterations);
        SettingsSection.intSpinner(toolCallsSpinner, 1, AgentLoopLimits.HARD_MAX_TOOL_CALLS, s.agentLoop.maxToolCalls);
        SettingsSection.intSpinner(durationSpinner, 5, (int) AgentLoopLimits.HARD_MAX_DURATION.toSeconds(),
                s.agentLoop.maxDurationSeconds);
        SettingsSection.intSpinner(identicalSpinner, 1, AgentLoopLimits.HARD_MAX_IDENTICAL_TOOL_CALLS,
                s.agentLoop.maxIdenticalToolCalls);
        SettingsSection.intSpinner(errorsSpinner, 1, AgentLoopLimits.HARD_MAX_CONSECUTIVE_ERRORS,
                s.agentLoop.maxConsecutiveErrors);
    }

    @Override
    public void apply(HarnessSettings s) {
        s.agentLoop.maxIterations = SettingsSection.spinnerValue(iterationsSpinner);
        s.agentLoop.maxToolCalls = SettingsSection.spinnerValue(toolCallsSpinner);
        s.agentLoop.maxDurationSeconds = SettingsSection.spinnerValue(durationSpinner);
        s.agentLoop.maxIdenticalToolCalls = SettingsSection.spinnerValue(identicalSpinner);
        s.agentLoop.maxConsecutiveErrors = SettingsSection.spinnerValue(errorsSpinner);
    }
}
