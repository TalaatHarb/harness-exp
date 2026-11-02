package net.talaatharb.harness.tools.dynamic;

import java.util.Map;

/**
 * Shared application interface for tools written by the agent at runtime.
 * <p>
 * Implementations must be a {@code public} class with a public no-arg constructor. They are compiled with the
 * JavaCompiler API and loaded in a dedicated {@link DynamicToolClassLoader} whose parent is the application class
 * loader, so only the JDK and this interface can be relied upon. Name, description and argument hint come from the
 * {@link DynamicToolSpec}; the class only implements behaviour.
 *
 * <pre>
 * import java.util.Map;
 * import net.talaatharb.harness.tools.dynamic.DynamicTool;
 *
 * public class WordCount implements DynamicTool {
 *     public String execute(Map&lt;String, Object&gt; arguments) {
 *         String text = String.valueOf(arguments.getOrDefault("text", ""));
 *         return String.valueOf(text.isBlank() ? 0 : text.trim().split("\\s+").length);
 *     }
 * }
 * </pre>
 */
public interface DynamicTool {

    /**
     * @param arguments JSON arguments supplied by the model (values are String, Number, Boolean, List or Map)
     * @return the textual result fed back to the model; throw to report an error
     */
    String execute(Map<String, Object> arguments) throws Exception;
}