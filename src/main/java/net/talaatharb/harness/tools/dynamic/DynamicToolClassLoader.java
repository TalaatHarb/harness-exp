package net.talaatharb.harness.tools.dynamic;

import java.util.Map;

/**
 * Dedicated loader per dynamic tool. Parent-first delegation to the application class loader exposes the JDK and
 * {@link DynamicTool}; the tool's own compiled classes are defined from memory. Replacing a tool simply drops the
 * old loader so its classes can be garbage collected.
 */
public final class DynamicToolClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    DynamicToolClassLoader(String toolName, Map<String, byte[]> classes, ClassLoader parent) {
        super("dynamic-tool-" + toolName, parent);
        this.classes = Map.copyOf(classes);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}