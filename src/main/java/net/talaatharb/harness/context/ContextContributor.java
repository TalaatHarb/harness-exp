package net.talaatharb.harness.context;

/**
 * One stage of the context-building pipeline. Contributors run in ascending
 * {@link #order()} and either add system sections or conversation messages.
 */
public interface ContextContributor {

    String id();

    String description();

    int order();

    void contribute(ContextAssembly assembly);
}
