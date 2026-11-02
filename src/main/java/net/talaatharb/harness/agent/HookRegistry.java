package net.talaatharb.harness.agent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Holds statically bound hooks plus hooks registered at runtime (e.g. by the UI or experiments). */
@Singleton
public class HookRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(HookRegistry.class);
    private final List<AgentHook> hooks = new CopyOnWriteArrayList<>();

    @Inject
    public HookRegistry(Set<AgentHook> bound) {
        hooks.addAll(bound);
    }

    public Runnable register(AgentHook hook) {
        hooks.add(hook);
        return () -> hooks.remove(hook);
    }

    public int size() {
        return hooks.size();
    }

    void fire(HookPoint point, AgentRunContext context) {
        for (AgentHook hook : hooks) {
            try {
                hook.on(point, context);
            } catch (RuntimeException e) {
                LOG.warn("Hook {} failed at {}", hook, point, e);
            }
        }
    }
}
