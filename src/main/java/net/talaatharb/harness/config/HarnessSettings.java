package net.talaatharb.harness.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import net.talaatharb.harness.agent.AgentLoopSettings;
import net.talaatharb.harness.approval.ApprovalSettings;
import net.talaatharb.harness.context.ContextSettings;
import net.talaatharb.harness.guardrails.GuardrailSettings;
import net.talaatharb.harness.llm.ModelSettings;
import net.talaatharb.harness.prompt.SystemPromptSettings;
import net.talaatharb.harness.reply.ReplySettings;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.speech.SpeechSettings;
import net.talaatharb.harness.tools.ToolSettings;

/**
 * Root of all user-tunable harness configuration. Each section of the
 * "Harness" settings window edits one of these fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HarnessSettings {
    public ModelSettings model = new ModelSettings();
    public SystemPromptSettings systemPrompt = new SystemPromptSettings();
    /**
     * Legacy: skills now live in the skills folder ({@link net.talaatharb.harness.skills.SkillRepository}). Only read
     * to migrate old settings files, then cleared, so it is normally absent from settings.json.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<SkillDefinition> skills;
    public ContextSettings context = new ContextSettings();
    public ToolSettings tools = new ToolSettings();
    public AgentLoopSettings agentLoop = new AgentLoopSettings();
    public GuardrailSettings guardrails = new GuardrailSettings();
    public ApprovalSettings approval = new ApprovalSettings();
    public ReplySettings reply = new ReplySettings();
    public StorageSettings storage = new StorageSettings();
    public SpeechSettings speech = new SpeechSettings();
}
