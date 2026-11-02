package net.talaatharb.harness.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default hook that logs every loop transition at debug level. */
public class LoggingHook implements AgentHook {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingHook.class);

    @Override
    public void on(HookPoint point, AgentRunContext c) {
        LOG.debug("[{}] {} iteration={} toolCalls={}", c.runId(), point, c.iteration(), c.toolCalls());
    }

    @Override
    public String toString() {
        return "LoggingHook";
    }
}
