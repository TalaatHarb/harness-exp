package net.talaatharb.harness.tools.dynamic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles a single Java source file fully in memory using the JavaCompiler API. */
public class DynamicToolCompiler {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "public\\s+(?:final\\s+|abstract\\s+)*class\\s+([A-Za-z_$][\\w$]*)");

    /** Result of a successful compilation: the public class name plus every class file produced. */
    public record Compiled(String className, Map<String, byte[]> classes) {
    }

    public boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    public Compiled compile(String source) throws DynamicToolException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new DynamicToolException(
                    "The Java compiler is not available: the harness must run on a JDK (with the jdk.compiler module).");
        }
        String className = className(source);
        String compilable = withDefaultImports(source);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8);
        MemoryFileManager files = new MemoryFileManager(standard);
        List<String> options = List.of("-classpath", classpath(), "-proc:none", "-g", "-encoding", "UTF-8");
        boolean ok;
        try {
            ok = compiler.getTask(null, files, diagnostics, options, null,
                    List.of(new SourceFile(className, compilable))).call();
        } catch (RuntimeException e) {
            throw new DynamicToolException("Compiler crashed: " + e.getMessage(), e);
        }
        if (!ok) {
            throw new DynamicToolException("Compilation failed:\n" + format(diagnostics));
        }
        return new Compiled(className, files.classes());
    }

    /**
     * Implicitly imports {@link DynamicTool} and {@code java.util.*} (models often omit them). The imports are
     * inserted on an existing line so compiler line numbers still match the agent's source.
     */
    static String withDefaultImports(String source) {
        String imports = " import " + DynamicTool.class.getName() + "; import java.util.*; ";
        Matcher pkg = PACKAGE.matcher(source);
        if (pkg.find()) {
            return source.substring(0, pkg.end()) + imports + source.substring(pkg.end());
        }
        return imports + source;
    }

    static String className(String source) throws DynamicToolException {
        Matcher cls = PUBLIC_CLASS.matcher(source);
        if (!cls.find()) {
            throw new DynamicToolException("The source must declare a public class implementing "
                    + DynamicTool.class.getName());
        }
        Matcher pkg = PACKAGE.matcher(source);
        return pkg.find() ? pkg.group(1) + "." + cls.group(1) : cls.group(1);
    }

    /** The JVM class path plus the location of {@link DynamicTool}, which may differ (e.g. module path, IDE runs). */
    static String classpath() {
        Set<String> entries = new LinkedHashSet<>();
        String cp = System.getProperty("java.class.path", "");
        for (String e : cp.split(File.pathSeparator)) {
            if (!e.isBlank()) {
                entries.add(e);
            }
        }
        try {
            var location = DynamicTool.class.getProtectionDomain().getCodeSource().getLocation();
            entries.add(Path.of(location.toURI()).toString());
        } catch (Exception ignored) {
            // no code source (unusual); fall back to java.class.path only
        }
        return String.join(File.pathSeparator, entries);
    }

    private static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            sb.append("line ").append(d.getLineNumber()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            if (sb.length() > DynamicToolLimits.MAX_DIAGNOSTICS_CHARS) {
                sb.setLength(DynamicToolLimits.MAX_DIAGNOSTICS_CHARS);
                sb.append("\n[more errors truncated]");
                break;
            }
        }
        return sb.toString().strip();
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class ClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ClassFile(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ClassFile> outputs = new LinkedHashMap<>();

        MemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                FileObject sibling) {
            ClassFile file = new ClassFile(className);
            outputs.put(className, file);
            return file;
        }

        Map<String, byte[]> classes() {
            Map<String, byte[]> result = new LinkedHashMap<>();
            outputs.forEach((name, file) -> result.put(name, file.bytes.toByteArray()));
            return result;
        }
    }
}