package net.talaatharb.harness.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Named;
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
import net.talaatharb.harness.history.ConversationStore;
import net.talaatharb.harness.memory.MemoryContextContributor;
import net.talaatharb.harness.memory.MemoryService;
import net.talaatharb.harness.memory.RecallTool;
import net.talaatharb.harness.memory.RememberTool;
import net.talaatharb.harness.rag.RagContextContributor;
import net.talaatharb.harness.rag.RagIngestTool;
import net.talaatharb.harness.rag.RagSearchTool;
import net.talaatharb.harness.rag.RagService;
import net.talaatharb.harness.skills.CreateSkillTool;
import net.talaatharb.harness.skills.SkillRepository;
import net.talaatharb.harness.llm.AnthropicModelProvider;
import net.talaatharb.harness.llm.ModelProvider;
import net.talaatharb.harness.llm.OllamaModelProvider;
import net.talaatharb.harness.llm.OpenAiModelProvider;
import net.talaatharb.harness.speech.SpeechToText;
import net.talaatharb.harness.speech.WhistleSpeechToText;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.builtin.CurrentTimeTool;
import net.talaatharb.harness.tools.builtin.ReadFileTool;
import net.talaatharb.harness.tools.dynamic.CreateToolTool;
import net.talaatharb.harness.tools.dynamic.DynamicToolRegistry;
import net.talaatharb.harness.tools.mcp.McpToolSource;
import net.talaatharb.harness.tools.mcp.McpClientManager;
import net.talaatharb.harness.ui.DialogApprovalGateway;

/**
 * Guice wiring. Every harness extension point is a multibinder so experiments
 * can add providers, tools, guardrails, context stages and hooks in one place.
 */
public class AppModule extends AbstractModule {
    private static final Logger LOG = LoggerFactory.getLogger(AppModule.class);
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

        bindModelProviders();

        Multibinder<Tool> tools = Multibinder.newSetBinder(binder(), Tool.class);
        tools.addBinding().to(CurrentTimeTool.class);
        tools.addBinding().to(net.talaatharb.harness.tools.builtin.CalculateTool.class);
        tools.addBinding().to(ReadFileTool.class);
        tools.addBinding().to(CreateToolTool.class);
        tools.addBinding().to(CreateSkillTool.class);
        tools.addBinding().to(RememberTool.class);
        tools.addBinding().to(RecallTool.class);
        tools.addBinding().to(RagSearchTool.class);
        tools.addBinding().to(RagIngestTool.class);
        bind(McpToolSource.class).to(McpClientManager.class);

        Multibinder<Guardrail> guardrails = Multibinder.newSetBinder(binder(), Guardrail.class);
        guardrails.addBinding().to(InputLengthGuardrail.class);
        guardrails.addBinding().to(BlockedTermsGuardrail.class);
        guardrails.addBinding().to(SecretRedactionGuardrail.class);

        Multibinder<ContextContributor> context = Multibinder.newSetBinder(binder(), ContextContributor.class);
        context.addBinding().to(Contributors.SystemPrompt.class);
        context.addBinding().to(Contributors.Skills.class);
        context.addBinding().to(MemoryContextContributor.class);
        context.addBinding().to(RagContextContributor.class);
        context.addBinding().to(Contributors.ToolCatalog.class);
        context.addBinding().to(Contributors.ReplyInstructions.class);
        context.addBinding().to(Contributors.PinnedContext.class);
        context.addBinding().to(Contributors.ConversationSummary.class);
        context.addBinding().to(Contributors.HistoryWindow.class);

        Multibinder<AgentHook> hooks = Multibinder.newSetBinder(binder(), AgentHook.class);
        hooks.addBinding().to(LoggingHook.class);

        bind(SpeechToText.class).to(WhistleSpeechToText.class);

        bindApprovalGateway();
    }

    /** Data locations from the settings' {@code storage} block, fixed for this run (changes apply after restart). */
    @Provides
    @Singleton
    StoragePaths storagePaths(SettingsStore store) {
        StoragePaths paths = StoragePaths.resolve(settingsPath, store.current().storage);
        LOG.info("Storage: skills={}, dynamic tools={}, history={}, models={}, memory={}, memory db={}, rag index={}",
                paths.skillsDir(), paths.dynamicToolsDir(), paths.historyDb(), paths.modelsDir(), paths.memoryDir(),
                paths.memoryDb(), paths.ragIndex());
        return paths;
    }

    @Provides
    @Named(SkillRepository.SKILLS_DIR)
    Path skillsDir(StoragePaths paths) {
        return paths.skillsDir();
    }

    @Provides
    @Named(DynamicToolRegistry.STORAGE_DIR)
    Path dynamicToolsDir(StoragePaths paths) {
        return paths.dynamicToolsDir();
    }

    @Provides
    @Named(ConversationStore.DB_PATH)
    Path historyDb(StoragePaths paths) {
        return paths.historyDb();
    }

    @Provides
    @Named(WhistleSpeechToText.MODELS_DIR)
    Path modelsDir(StoragePaths paths) {
        return paths.modelsDir();
    }

    @Provides
    @Named(MemoryService.MEMORY_DIR)
    Path memoryDir(StoragePaths paths) {
        return paths.memoryDir();
    }

    @Provides
    @Named(MemoryService.MEMORY_DB)
    Path memoryDb(StoragePaths paths) {
        return paths.memoryDb();
    }

    @Provides
    @Named(RagService.RAG_INDEX)
    Path ragIndex(StoragePaths paths) {
        return paths.ragIndex();
    }

    /** Overridable so headless tests can auto-approve. */
    protected void bindModelProviders() {
        Multibinder<ModelProvider> providers = Multibinder.newSetBinder(binder(), ModelProvider.class);
        providers.addBinding().to(OllamaModelProvider.class);
        providers.addBinding().to(OpenAiModelProvider.class);
        providers.addBinding().to(AnthropicModelProvider.class);
    }

    protected void bindApprovalGateway() {
        bind(ApprovalGateway.class).to(DialogApprovalGateway.class);
    }
}
