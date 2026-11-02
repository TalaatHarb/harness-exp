# harness-exp

Harness engineering experiment: a JavaFX desktop app that wraps local and cloud AI models (via the
[Ollama API](https://github.com/ollama4j/ollama4j)) in a configurable **agent harness**.

The main window is intentionally just a ChatGPT-like chat. Everything that shapes the agent lives in a
separate **⚙ Harness** settings window, one tab per harness concern.

## Harness sections

| Section | What it controls | Code |
|---|---|---|
| Model | Provider, local (`http://localhost:11434`) or cloud (`https://ollama.com` + API key), model, timeout, streaming | `llm` |
| System prompt | Template with `{{date}}`, `{{time}}`, `{{model}}`, `{{os}}`, `{{user}}` variables | `prompt` |
| Skills | Named instruction packs, injected always or only when trigger words match | `skills` |
| Context builder | Ordered `ContextContributor` pipeline (toggleable), history window, pinned context | `context` |
| Tools + MCP | Built-in tools, enable/disable, output cap, MCP server definitions (stdio/http) | `tools` |
| Agent loop | Iteration/tool-call/duration/repeat/error limits (clamped by hard ceilings in `AgentLoopLimits`) and `HookPoint`s | `agent` |
| Guardrails | Input/tool-call/output guardrails: length limit, blocked terms, secret redaction | `guardrails` |
| Human approval | Never / risky tools / all tools, always-allowed list, timeout (denies on timeout) | `approval` |
| Reply | Plain text or **agentic UI**: the model emits ```` ```ui ```` JSON blocks rendered as live JavaFX components | `reply` |

## Architecture

```
user msg -> INPUT guardrails -> loop {
   ContextBuilder (contributors) -> ModelProvider.chat
   -> tool call?  no  -> OUTPUT guardrails -> reply renderer (text / agentic UI)
                  yes -> TOOL_CALL guardrails -> human approval -> Tool.execute -> result back to model
} bounded by AgentLoopLimits, with hooks at every HookPoint
```

Extension points are Guice multibinders in `config/AppModule`: `ModelProvider`, `Tool`, `Guardrail`,
`ContextContributor`, `AgentHook`, plus `McpToolSource` and `ApprovalGateway` bindings.

Protocols (provider-agnostic, plain text):

- Tool call: ```` ```tool_call {"name": "read_file", "arguments": {"path": "README.md"}} ``` ````
- Generated UI: ```` ```ui {"type": "card", "text": "Title", "children": [{"type": "button", "text": "Go", "action": "message sent back"}]} ``` ````
  Supported types: column, row, card, heading, text, button, list, table, progress, badge, separator.

MCP is configured but not yet connected (`UnconnectedMcpToolSource` is the placeholder to replace).

## Running

Requirements: Java 17+, Maven, and [Ollama](https://ollama.com) running locally (or an ollama.com API key).

```sh
ollama pull qwen2.5:3b
mvn javafx:run          # run from source
mvn verify              # tests + fat jar in target/
java -jar target/harness-exp-0.1.0-jar-with-dependencies.jar
```

Settings are stored in `~/.harness-exp/settings.json` (override with `-Dharness.settings=<path>`).

Every chat message has a **⧉ Copy** button underneath it (assistant replies copy the raw model text, including
`ui` blocks). Errors (model/tool failures, guardrail blocks, approval timeouts, malformed protocol blocks,
uncaught exceptions) are logged via SLF4J to stderr; e.g. `-Dorg.slf4j.simpleLogger.logFile=harness.log`
writes them to a file.

## CI / Releases

- `.github/workflows/project-build.yml`: `mvn verify` on every push/PR.
- `.github/workflows/release-build.yml`: on `v*` tags, builds fat jars for Linux/Windows/macOS and a Windows
  portable app (jpackage) and attaches them to the GitHub release.

## License

Apache 2.0, see [LICENSE](LICENSE).
