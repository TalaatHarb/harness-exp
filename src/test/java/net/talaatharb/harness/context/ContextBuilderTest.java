package net.talaatharb.harness.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.llm.ChatMessage;
import net.talaatharb.harness.llm.Role;
import net.talaatharb.harness.skills.SkillRepository;

class ContextBuilderTest {
    @TempDir
    static Path tempDir;
    private static ContextBuilder builder;

    @BeforeAll
    static void setUp() {
        builder = new ContextBuilder(Set.of(new Contributors.HistoryWindow(), new Contributors.SystemPrompt(),
                new Contributors.Skills(new SkillRepository(tempDir.resolve("skills"), null)),
                new Contributors.PinnedContext()));
    }

    @Test
    void injectsOnlyTriggeredSkillsAndPinnedContext() {
        HarnessSettings s = new HarnessSettings();
        s.context.pinnedContext = "Project: harness";

        String system = builder.build(s, List.of(ChatMessage.user("please summarize this"))).get(0).content();

        assertTrue(system.contains("Summarizer"));
        assertFalse(system.contains("Code reviewer"));
        assertTrue(system.contains("Project: harness"));
    }

    @Test
    void appliesHistoryWindowAndDisabledContributors() {
        HarnessSettings s = new HarnessSettings();
        s.context.maxHistoryMessages = 3;
        s.context.disabledContributors.add("system-prompt");
        s.context.disabledContributors.add("skills");
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(i % 2 == 0 ? ChatMessage.user("u" + i) : ChatMessage.assistant("a" + i));
        }

        List<ChatMessage> context = builder.build(s, history);

        assertEquals(3, context.size());
        assertEquals(Role.ASSISTANT, context.get(0).role());
        assertEquals("u8", context.get(1).content());
    }

    @Test
    void rendersTemplateVariables() {
        HarnessSettings s = new HarnessSettings();
        s.systemPrompt.template = "model={{model}} unknown={{nope}}";
        s.model.model = "m1";
        assertEquals("model=m1 unknown={{nope}}",
                builder.build(s, List.of(ChatMessage.user("x"))).get(0).content().lines().findFirst().orElse(""));
    }
}
