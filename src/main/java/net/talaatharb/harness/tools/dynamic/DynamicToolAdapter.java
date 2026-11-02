package net.talaatharb.harness.tools.dynamic;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Exposes a loaded {@link DynamicTool} as a regular harness {@link Tool}, bounded by an execution timeout. */
final class DynamicToolAdapter implements Tool {
    static final String SOURCE = "dynamic";

    private final DynamicToolSpec spec;
    private final DynamicTool delegate;
    private final ExecutorService executor;
    private final Duration timeout;

    DynamicToolAdapter(DynamicToolSpec spec, DynamicTool delegate, ExecutorService executor, Duration timeout) {
        this.spec = spec;
        this.delegate = delegate;
        this.executor = executor;
        this.timeout = timeout;
    }

    DynamicToolSpec spec() {
        return spec;
    }

    @Override
    public String name() {
        return spec.name;
    }

    @Override
    public String description() {
        return spec.description;
    }

    @Override
    public String argumentsHint() {
        return spec.argumentsHint == null || spec.argumentsHint.isBlank() ? "{}" : spec.argumentsHint;
    }

    /** Agent-written code runs unsandboxed in-process, so every call needs approval (see {@link #alwaysRequiresApproval()}). */
    @Override
    public RiskLevel risk() {
        return RiskLevel.SENSITIVE;
    }

    /** The tool name can be re-bound to new code, so an "always allow" for the name is never honoured. */
    @Override
    public boolean alwaysRequiresApproval() {
        return true;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws InterruptedException {
        Future<String> future = executor.submit(() -> delegate.execute(arguments == null ? Map.of() : arguments));
        try {
            String output = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return ToolResult.ok(output == null ? "(no output)" : output);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.error("Dynamic tool '" + spec.name + "' timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.error("Dynamic tool '" + spec.name + "' threw " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }
}