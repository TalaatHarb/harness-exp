package net.talaatharb.harness.tools.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Configuration for a Model Context Protocol server. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {
    public enum Transport {
        STDIO, HTTP
    }

    public String name = "";
    public Transport transport = Transport.STDIO;
    /** Command line for {@link Transport#STDIO} servers, e.g. {@code npx -y @modelcontextprotocol/server-filesystem .}. */
    public String command = "";
    /** Endpoint for {@link Transport#HTTP} (streamable HTTP) servers. */
    public String url = "";
    public boolean enabled = true;

    public McpServerConfig() {
    }

    public McpServerConfig(String name, Transport transport, String command, String url, boolean enabled) {
        this.name = name;
        this.transport = transport;
        this.command = command;
        this.url = url;
        this.enabled = enabled;
    }

    public String endpoint() {
        return transport == Transport.HTTP ? url : command;
    }
}
