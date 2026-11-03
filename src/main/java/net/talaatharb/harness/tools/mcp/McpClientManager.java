package net.talaatharb.harness.tools.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.tools.Tool;

/**
 * Keeps one connection per enabled MCP server in the applied settings. Servers are (re)connected in the background
 * whenever the settings change; {@link #tools} only returns what is connected right now, so it never blocks.
 */
@Singleton
public class McpClientManager implements McpToolSource, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(McpClientManager.class);

    private enum State {
        CONNECTING, CONNECTED, FAILED
    }

    private static final class Entry {
        final McpServerConfig config;
        volatile State state = State.CONNECTING;
        volatile McpClient client;
        volatile List<McpClient.ToolInfo> tools = List.of();
        volatile String message = "";
        volatile boolean removed;

        Entry(McpServerConfig config) {
            this.config = config;
        }
    }

    private final SettingsStore store;
    private final UnaryOperator<String> env;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-connect");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    @Inject
    public McpClientManager(SettingsStore store) {
        this(store, System::getenv);
    }

    McpClientManager(SettingsStore store, UnaryOperator<String> env) {
        this.store = store;
        this.env = env;
        store.addListener(this::syncWithSettings);
        syncWithSettings();
    }

    private HarnessSettings settings() {
        return store.current();
    }

    private void syncWithSettings() {
        HarnessSettings s = settings();
        sync(s.tools.toolsEnabled ? s.tools.mcpServers : List.of());
    }

    /** Opens connections for new/changed enabled servers and closes the rest. */
    synchronized void sync(List<McpServerConfig> servers) {
        if (closed) {
            return;
        }
        Set<String> wanted = new HashSet<>();
        for (McpServerConfig config : servers) {
            if (!config.enabled || config.name == null || config.name.isBlank()) {
                continue;
            }
            wanted.add(config.name);
            Entry existing = entries.get(config.name);
            if (existing != null && existing.config.connectionKey().equals(config.connectionKey())) {
                continue;
            }
            if (existing != null) {
                drop(existing);
            }
            start(config.copy());
        }
        for (Entry e : List.copyOf(entries.values())) {
            if (!wanted.contains(e.config.name)) {
                entries.remove(e.config.name);
                drop(e);
            }
        }
    }

    private void start(McpServerConfig config) {
        Entry entry = new Entry(config);
        entries.put(config.name, entry);
        fire();
        executor.submit(() -> connect(entry));
    }

    private void connect(Entry entry) {
        Duration timeout = Duration.ofSeconds(Math.max(5, settings().tools.mcpConnectTimeoutSeconds));
        try {
            McpClient client = McpClient.connect(entry.config, env, timeout);
            if (entry.removed) {
                client.close();
                return;
            }
            entry.client = client;
            client.transport().onNotification(method -> {
                if ("notifications/tools/list_changed".equals(method)) {
                    executor.submit(() -> refreshTools(entry));
                }
            });
            client.transport().onClosed(reason -> {
                if (!entry.removed) {
                    entry.state = State.FAILED;
                    entry.message = "disconnected: " + reason;
                    fire();
                }
            });
            refreshTools(entry);
            if (entry.state != State.FAILED) {
                entry.state = State.CONNECTED;
                LOG.info("MCP server '{}' ready with {} tool(s): {}", entry.config.name, entry.tools.size(),
                        entry.tools.stream().map(McpClient.ToolInfo::name).toList());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry.state = State.FAILED;
            entry.message = "interrupted";
        } catch (Exception e) {
            LOG.error("Could not connect to MCP server '{}' ({})", entry.config.name, entry.config.endpoint(), e);
            entry.state = State.FAILED;
            entry.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        fire();
    }

    private void refreshTools(Entry entry) {
        McpClient client = entry.client;
        if (client == null || entry.removed) {
            return;
        }
        try {
            entry.tools = List.copyOf(client.listTools());
        } catch (Exception e) {
            LOG.error("Could not list tools of MCP server '{}'", entry.config.name, e);
            entry.state = State.FAILED;
            entry.message = "tools/list failed: " + e.getMessage();
        }
        fire();
    }

    private void drop(Entry entry) {
        entry.removed = true;
        McpClient client = entry.client;
        if (client != null) {
            executor.submit(client::close);
        }
        fire();
    }

    @Override
    public List<Tool> tools(List<McpServerConfig> servers) {
        Set<String> enabled = new HashSet<>();
        servers.stream().filter(c -> c.enabled).forEach(c -> enabled.add(c.name));
        List<Entry> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(entries.values());
        }
        List<Tool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Entry e : snapshot) {
            if (e.state != State.CONNECTED || !enabled.contains(e.config.name) || e.client == null) {
                continue;
            }
            for (McpClient.ToolInfo info : e.tools) {
                String name = names.add(info.name()) ? info.name() : uniqueName(e.config.name, info.name(), names);
                tools.add(new McpTool(name, e.client, info,
                        () -> Duration.ofSeconds(Math.max(1, settings().tools.mcpToolTimeoutSeconds))));
            }
        }
        return tools;
    }

    /** Same tool name on two servers: the second becomes {@code <server>_<tool>}. */
    private static String uniqueName(String server, String tool, Set<String> taken) {
        String base = server.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + tool;
        String name = base;
        for (int i = 2; !taken.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    @Override
    public String status(McpServerConfig server) {
        if (!server.enabled) {
            return "disabled";
        }
        if (!settings().tools.toolsEnabled) {
            return "tool calling is off";
        }
        Entry e;
        synchronized (this) {
            e = entries.get(server.name);
        }
        if (e == null || !e.config.connectionKey().equals(server.connectionKey())) {
            return "not connected (apply to connect)";
        }
        return switch (e.state) {
            case CONNECTING -> "connecting…";
            case CONNECTED -> "connected · " + e.tools.size() + " tool(s)"
                    + (e.client == null || e.client.serverInfo().isEmpty() ? "" : " · " + e.client.serverInfo());
            case FAILED -> "failed: " + e.message;
        };
    }

    @Override
    public synchronized void reconnect(String serverName) {
        Entry e = entries.remove(serverName);
        if (e != null) {
            drop(e);
            start(e.config.copy());
        } else {
            syncWithSettings();
        }
    }

    @Override
    public List<McpClient.ToolInfo> test(McpServerConfig server) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(5, settings().tools.mcpConnectTimeoutSeconds));
        try (McpClient client = McpClient.connect(server, env, timeout)) {
            return client.listTools();
        }
    }

    @Override
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOG.warn("MCP listener failed", e);
            }
        }
    }

    @Override
    public void close() {
        List<Entry> all;
        synchronized (this) {
            closed = true;
            all = List.copyOf(entries.values());
            entries.clear();
        }
        all.forEach(e -> {
            e.removed = true;
            if (e.client != null) {
                e.client.close();
            }
        });
        executor.shutdownNow();
    }
}
