package net.talaatharb.harness.tools.dynamic;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import net.talaatharb.harness.tools.Tool;

/**
 * Creates, persists and reloads agent-written tools. Only tools that compiled, loaded and instantiated successfully
 * are written to the storage folder (one {@code <name>.json} {@link DynamicToolSpec} each), and those files are
 * recompiled when the application starts.
 */
@Singleton
public class DynamicToolRegistry {
    public static final String STORAGE_DIR = "dynamicToolsDir";
    private static final Logger LOG = LoggerFactory.getLogger(DynamicToolRegistry.class);

    private final Path storageDir;
    private final Duration executionTimeout;
    private final DynamicToolCompiler compiler = new DynamicToolCompiler();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, DynamicToolAdapter> tools = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dynamic-tool-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    @Inject
    public DynamicToolRegistry(@Named(STORAGE_DIR) Path storageDir) {
        this(storageDir, Duration.ofSeconds(DynamicToolLimits.EXECUTION_TIMEOUT_SECONDS));
    }

    DynamicToolRegistry(Path storageDir, Duration executionTimeout) {
        this.storageDir = storageDir;
        this.executionTimeout = executionTimeout;
        loadStored();
    }

    public Path storageDir() {
        return storageDir;
    }

    public boolean compilerAvailable() {
        return compiler.available();
    }

    public synchronized List<Tool> tools() {
        return new ArrayList<>(tools.values());
    }

    public synchronized Optional<DynamicToolSpec> spec(String name) {
        return Optional.ofNullable(tools.get(name)).map(DynamicToolAdapter::spec);
    }

    /**
     * Validates, compiles, loads and instantiates the tool, then persists it. Re-using a name replaces the tool.
     *
     * @param reservedNames names owned by other tool sources (built-in, MCP) that must not be shadowed
     */
    public Tool create(DynamicToolSpec input, Set<String> reservedNames) throws DynamicToolException {
        DynamicToolSpec spec = validate(input, reservedNames);
        DynamicToolAdapter adapter = load(spec);
        spec.createdAt = OffsetDateTime.now().toString();
        synchronized (this) {
            if (!tools.containsKey(spec.name) && tools.size() >= DynamicToolLimits.MAX_DYNAMIC_TOOLS) {
                throw new DynamicToolException("Too many dynamic tools (max " + DynamicToolLimits.MAX_DYNAMIC_TOOLS
                        + "); ask the user to delete some first.");
            }
            persist(spec);
            boolean replaced = tools.put(spec.name, adapter) != null;
            LOG.info("{} dynamic tool '{}' ({})", replaced ? "Replaced" : "Created", spec.name,
                    file(spec.name));
        }
        return adapter;
    }

    public synchronized boolean remove(String name) {
        boolean removed = tools.remove(name) != null;
        if (DynamicToolLimits.NAME_PATTERN.matcher(name).matches()) {
            try {
                removed |= Files.deleteIfExists(file(name));
            } catch (IOException e) {
                LOG.error("Could not delete dynamic tool file for '{}'", name, e);
            }
        }
        if (removed) {
            LOG.info("Removed dynamic tool '{}'", name);
        }
        return removed;
    }

    private static DynamicToolSpec validate(DynamicToolSpec in, Set<String> reservedNames)
            throws DynamicToolException {
        if (in == null) {
            throw new DynamicToolException("Missing tool definition");
        }
        String name = in.name == null ? "" : in.name.trim();
        if (!DynamicToolLimits.NAME_PATTERN.matcher(name).matches()) {
            throw new DynamicToolException((name.isEmpty() ? "Missing argument 'name'" : "Invalid name '" + name + "'")
                    + ": use snake_case matching " + DynamicToolLimits.NAME_PATTERN.pattern());
        }
        if (reservedNames.contains(name)) {
            throw new DynamicToolException("The name '" + name + "' is used by a built-in tool; choose another.");
        }
        String description = in.description == null ? "" : in.description.trim();
        if (description.isEmpty() || description.length() > DynamicToolLimits.MAX_DESCRIPTION_CHARS) {
            throw new DynamicToolException("A description of 1-" + DynamicToolLimits.MAX_DESCRIPTION_CHARS
                    + " characters is required");
        }
        if (in.sourceCode == null || in.sourceCode.isBlank()) {
            throw new DynamicToolException("Missing Java source: send the create_tool call again and put the complete "
                    + "source in a ```java block in the SAME reply, right after the tool_call block.");
        }
        if (in.sourceCode.length() > DynamicToolLimits.MAX_SOURCE_CHARS) {
            throw new DynamicToolException("sourceCode exceeds " + DynamicToolLimits.MAX_SOURCE_CHARS + " characters");
        }
        String hint = in.argumentsHint == null || in.argumentsHint.isBlank() ? "{}" : in.argumentsHint.trim();
        return new DynamicToolSpec(name, description, hint, in.sourceCode);
    }

    private DynamicToolAdapter load(DynamicToolSpec spec) throws DynamicToolException {
        DynamicToolCompiler.Compiled compiled = compiler.compile(spec.sourceCode);
        ClassLoader loader = new DynamicToolClassLoader(spec.name, compiled.classes(),
                DynamicTool.class.getClassLoader());
        try {
            Class<?> type = Class.forName(compiled.className(), true, loader);
            if (!DynamicTool.class.isAssignableFrom(type)) {
                throw new DynamicToolException(compiled.className() + " does not implement "
                        + DynamicTool.class.getName());
            }
            DynamicTool instance = (DynamicTool) type.getDeclaredConstructor().newInstance();
            return new DynamicToolAdapter(spec, instance, executor, executionTimeout);
        } catch (DynamicToolException e) {
            throw e;
        } catch (NoSuchMethodException e) {
            throw new DynamicToolException(compiled.className() + " needs a public no-arg constructor", e);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DynamicToolException("Could not load " + compiled.className() + ": "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
        }
    }

    private Path file(String name) {
        return storageDir.resolve(name + ".json");
    }

    private void persist(DynamicToolSpec spec) throws DynamicToolException {
        try {
            Files.createDirectories(storageDir);
            Path target = file(spec.name);
            Path tmp = storageDir.resolve(spec.name + ".json.tmp");
            mapper.writeValue(tmp.toFile(), spec);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("Could not persist dynamic tool '{}' to {}", spec.name, storageDir, e);
            throw new DynamicToolException("Could not save the tool: " + e.getMessage(), e);
        }
    }

    private void loadStored() {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            LOG.error("Could not list dynamic tools in {}", storageDir, e);
            return;
        }
        files.sort(Comparator.naturalOrder());
        for (Path f : files) {
            try {
                DynamicToolSpec stored = mapper.readValue(f.toFile(), DynamicToolSpec.class);
                DynamicToolSpec spec = validate(stored, Set.of());
                spec.createdAt = stored.createdAt;
                if (!f.getFileName().toString().equals(spec.name + ".json")) {
                    LOG.warn("Skipping {}: file name does not match tool name '{}'", f, spec.name);
                    continue;
                }
                tools.put(spec.name, load(spec));
                LOG.info("Loaded dynamic tool '{}'", spec.name);
            } catch (IOException | DynamicToolException e) {
                LOG.error("Could not load stored dynamic tool {}: {}", f, e.getMessage(), e);
            }
        }
    }
}