package net.talaatharb.harness.agent;

/**
 * The conversation an agent run is answering, visible to the context stages and tools that run on the same thread
 * (tools only get their JSON arguments). {@link AgentLoop} sets it for the duration of a run.
 */
public final class ConversationScope {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ConversationScope() {
    }

    /** @return the conversation id of the run on this thread, or {@code null} */
    public static String current() {
        return CURRENT.get();
    }

    /** @return the previous value, to pass to {@link #restore} */
    public static String enter(String conversationId) {
        String previous = CURRENT.get();
        set(conversationId);
        return previous;
    }

    public static void restore(String previous) {
        set(previous);
    }

    private static void set(String id) {
        if (id == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(id);
        }
    }
}
