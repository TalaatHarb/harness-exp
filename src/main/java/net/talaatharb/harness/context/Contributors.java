package net.talaatharb.harness.context;

import java.util.List;

import com.google.inject.Inject;

import net.talaatharb.harness.prompt.PromptTemplate;
import net.talaatharb.harness.reply.ReplyFormat;
import net.talaatharb.harness.reply.ReplySettings;
import net.talaatharb.harness.skills.SkillDefinition;
import net.talaatharb.harness.skills.SkillRepository;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolCallParser;
import net.talaatharb.harness.tools.ToolRegistry;

/** Built-in context contributors. Each is a small, independently switchable stage. */
public final class Contributors {
    private Contributors() {
    }

    public static class SystemPrompt implements ContextContributor {
        @Override
        public String id() {
            return "system-prompt";
        }

        @Override
        public String description() {
            return "Rendered system prompt template";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void contribute(ContextAssembly a) {
            a.addSystemSection(PromptTemplate.render(a.settings().systemPrompt.template,
                    PromptTemplate.standardVariables(a.settings().model.model)));
        }
    }

    public static class Skills implements ContextContributor {
        private final SkillRepository skills;

        @Inject
        public Skills(SkillRepository skills) {
            this.skills = skills;
        }

        @Override
        public String id() {
            return "skills";
        }

        @Override
        public String description() {
            return "Instructions of enabled skills (skills folder) matching the latest message";
        }

        @Override
        public int order() {
            return 100;
        }

        @Override
        public void contribute(ContextAssembly a) {
            List<SkillDefinition> active = skills.skills().stream()
                    .filter(s -> s.appliesTo(a.latestUserMessage())).toList();
            if (active.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("## Active skills");
            for (SkillDefinition s : active) {
                sb.append("\n### ").append(s.name).append("\n").append(s.instructions);
            }
            a.addSystemSection(sb.toString());
        }
    }

    public static class ToolCatalog implements ContextContributor {
        private final ToolRegistry tools;

        @Inject
        public ToolCatalog(ToolRegistry tools) {
            this.tools = tools;
        }

        @Override
        public String id() {
            return "tool-catalog";
        }

        @Override
        public String description() {
            return "Available tools and the tool-call protocol";
        }

        @Override
        public int order() {
            return 200;
        }

        @Override
        public void contribute(ContextAssembly a) {
            List<Tool> enabled = tools.enabled(a.settings());
            if (enabled.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("## Tools\nYou can call tools. To call one, reply with ONLY:\n```")
                    .append(ToolCallParser.FENCE)
                    .append("\n{\"name\": \"<tool name>\", \"arguments\": { ... }}\n```\n")
                    .append("You will receive the result in the next message. Call one tool at a time; ")
                    .append("when you have enough information, answer the user normally.\nAvailable tools:");
            for (Tool t : enabled) {
                sb.append("\n- ").append(t.name()).append(": ").append(t.description())
                        .append(" Arguments: ").append(t.argumentsHint());
            }
            a.addSystemSection(sb.toString());
        }
    }

    public static class ReplyInstructions implements ContextContributor {
        @Override
        public String id() {
            return "reply-format";
        }

        @Override
        public String description() {
            return "Agentic UI reply instructions (when enabled)";
        }

        @Override
        public int order() {
            return 300;
        }

        @Override
        public void contribute(ContextAssembly a) {
            if (a.settings().reply.mode == ReplySettings.Mode.AGENTIC_UI) {
                a.addSystemSection(ReplyFormat.MODEL_INSTRUCTIONS);
            }
        }
    }

    public static class PinnedContext implements ContextContributor {
        @Override
        public String id() {
            return "pinned-context";
        }

        @Override
        public String description() {
            return "Static pinned notes from settings";
        }

        @Override
        public int order() {
            return 400;
        }

        @Override
        public void contribute(ContextAssembly a) {
            String pinned = a.settings().context.pinnedContext;
            if (pinned != null && !pinned.isBlank()) {
                a.addSystemSection("## Pinned context\n" + pinned);
            }
        }
    }

    public static class HistoryWindow implements ContextContributor {
        @Override
        public String id() {
            return "history-window";
        }

        @Override
        public String description() {
            return "Most recent conversation messages (sliding window)";
        }

        @Override
        public int order() {
            return 1000;
        }

        @Override
        public void contribute(ContextAssembly a) {
            List<net.talaatharb.harness.llm.ChatMessage> history = a.history();
            int max = Math.max(1, a.settings().context.maxHistoryMessages);
            a.conversation().addAll(history.subList(Math.max(0, history.size() - max), history.size()));
        }
    }
}
