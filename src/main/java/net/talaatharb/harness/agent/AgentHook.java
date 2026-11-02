package net.talaatharb.harness.agent;

/**
 * Observes or steers the agent loop. Hooks run synchronously on the agent
 * thread and may call {@link AgentRunContext#abort(String)} to stop the run.
 */
@FunctionalInterface
public interface AgentHook {

    void on(HookPoint point, AgentRunContext context);
}
