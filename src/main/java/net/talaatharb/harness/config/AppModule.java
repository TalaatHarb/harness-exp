package net.talaatharb.harness.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

import net.talaatharb.harness.agent.AgentHook;
import net.talaatharb.harness.agent.LoggingHook;
import net.talaatharb.harness.approval.ApprovalGateway;
import net.talaatharb.harness.context.ContextContributor;
import net.talaatharb.harness.context.Contributors;
import net.talaatharb.harness.guardrails.BlockedTermsGuardrail;
import net.talaatharb.harness.guardrails.Guardrail;
import net.talaatharb.harness.guardrails.InputLengthGuardrail;
import net.talaatharb.harness.guardrails.SecretRedactionGuardrail;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.OllamaModelProvider;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.builtin.CurrentTimeTool;
import net.talaatharb.harness.tools.builtin.ReadFileTool;
import net.talaatharb.harness.tools.mcp.McpToolSource;
import net.talaatharb.harness.tools.mcp.UnconnectedMcpToolSource;
import net.talaatharb.harness.ui.DialogApprovalGateway;

/**
 * Guice wiring. Every harness extension point is a multibinder so experiments
 * can add providers, tools, guardrails, context stages and hooks in one place.
 */
public class AppModule extends AbstractModule {
    private final Path settingsPath;

    public AppModule() {
        this(defaultSettingsPath());
    }

    public AppModule(Path settingsPath) {
        this.settingsPath = settingsPath;
    }

    public static Path defaultSettingsPath() {
        String override = System.getProperty("harness.settings");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".harness-exp", "settings.json");
    }

    @Override
    protected void configure() {
        bind(Path.class).annotatedWith(Names.named(SettingsStore.SETTINGS_PATH)).toInstance(settingsPath);

        Multibinder<ModelProvider> providers = Multibinder.newSetBinder(binder(), ModelProvider.class);
        providers.addBinding().to(OllamaModelProvider.class);

        Multibinder<Tool> tools = Multibinder.newSetBinder(binder(), Tool.class);
        tools.addBinding().to(CurrentTimeTool.class);
        tools.addBinding().to(ReadFileTool.class);
        bind(McpToolSource.class).to(UnconnectedMcpToolSource.class);

        Multibinder<Guardrail> guardrails = Multibinder.newSetBinder(binder(), Guardrail.class);
        guardrails.addBinding().to(InputLengthGuardrail.class);
        guardrails.addBinding().to(BlockedTermsGuardrail.class);
        guardrails.addBinding().to(SecretRedactionGuardrail.class);

        Multibinder<ContextContributor> context = Multibinder.newSetBinder(binder(), ContextContributor.class);
        context.addBinding().to(Contributors.SystemPrompt.class);
        context.addBinding().to(Contributors.Skills.class);
        context.addBinding().to(Contributors.ToolCatalog.class);
        context.addBinding().to(Contributors.ReplyInstructions.class);
        context.addBinding().to(Contributors.PinnedContext.class);
        context.addBinding().to(Contributors.HistoryWindow.class);

        Multibinder<AgentHook> hooks = Multibinder.newSetBinder(binder(), AgentHook.class);
        hooks.addBinding().to(LoggingHook.class);

        bindApprovalGateway();
    }

    /** Overridable so headless tests can auto-approve. */
    protected void bindApprovalGateway() {
        bind(ApprovalGateway.class).to(DialogApprovalGateway.class);
    }
}
