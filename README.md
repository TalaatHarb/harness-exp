# harness-exp

Harness engineering experiment: a JavaFX desktop app that wraps local and cloud AI models (via the
[Ollama API](https://github.com/ollama4j/ollama4j)) in a configurable **agent harness**.

The main window is intentionally just a ChatGPT-like chat. Everything that shapes the agent lives in a
separate **⚙ Harness** settings window, one tab per harness concern.

## Chat window

- **Copy** under every request and response, and **↻ Regenerate** under every response. Regenerate rewinds the
  conversation to that request and runs it again; later messages are discarded.
- **☰ History**: a collapsible sidebar listing saved conversations, with title search, rename (button, F2,
  double-click or right-click) and delete. Selecting one
  restores its bubbles and tool traces; new messages continue it with the full transcript as context.
  Conversations are stored with [MapDB](https://github.com/jankotek/mapdb) in `~/.harness-exp/history.db`
  (package `history`). Each record is a JSON transcript plus per-turn outcomes, and the file is transactional.
  If the file is locked by another running instance, the app falls back to in-memory history and shows a warning.

## Harness sections

| Section | What it controls | Code |
|---|---|---|
| Model | Provider, local (`http://localhost:11434`) or cloud (`https://ollama.com` + API key), model, timeout, streaming | `llm` |
| System prompt | Template with `{{date}}`, `{{time}}`, `{{model}}`, `{{os}}`, `{{user}}` variables | `prompt` |
| Skills | Edits the skills folder (`~/.harness-exp/skills/<id>/SKILL.md`): new / save / delete / revert, plus **⟳ Refresh** to pick up outside edits or agent-created skills. Skills are injected always or only when trigger words match | `skills` folder |
| Context builder | Ordered `ContextContributor` pipeline (toggleable), history window, pinned context, attachment limits | `context` |
| Tools + MCP | Built-in tools, enable/disable, output cap, MCP server definitions (stdio/http) | `tools` |
| Agent loop | Iteration/tool-call/duration/repeat/error limits (clamped by hard ceilings in `AgentLoopLimits`) and `HookPoint`s | `agent` |
| Guardrails | Input/tool-call/output guardrails: length limit, blocked terms, secret redaction | `guardrails` |
| Human approval | Never / risky tools / all tools, always-allowed list, timeout (denies and closes the dialog). Tools that run agent-written code (`create_tool`, dynamic tools) or rewrite its instructions (`create_skill`) and `DANGEROUS` tools need approval on **every** call, whatever the policy, and can't be "always allowed" | `approval` |
| Reply | Plain text or **agentic UI**: the model emits ```` ```ui ```` JSON blocks rendered as live JavaFX components | `reply` |
| Storage | External locations for the skills folder, dynamic tools folder and MapDB history file (browse or type; blank = default). Applies after restart | `storage` |

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

Malformed `tool_call` blocks are not shown as answers; the loop sends the parse problem back to the model
so it can retry (bounded by the iteration limit).

## Dynamic tools (agent-built, sensitive)

The agent can write its own tools with the built-in `create_tool` tool (`tools/dynamic`):

1. The model sends a `tool_call` for `create_tool` (`name`, `description`, `argumentsHint`) and puts the Java source
   in a ```` ```java ```` block in the same reply, or passes it as `sourceCode`.
2. The source must have one public class that implements
   `net.talaatharb.harness.tools.dynamic.DynamicTool` (`String execute(Map<String, Object> arguments)`).
   `DynamicTool` and `java.util.*` are imported automatically.
3. The `JavaCompiler` API compiles the source in memory. The classes are loaded in a dedicated
   `DynamicToolClassLoader` (one per tool), and the new tool is added to the tool list immediately.
4. Only tools that compile and load successfully are saved, as JSON, to `~/.harness-exp/dynamic-tools/<name>.json`.
   They are reloaded at startup. The Tools settings section lets you view their source and delete them.

Limits are in `DynamicToolLimits`: name pattern, source size, maximum tool count, and an execution timeout.
`create_tool` and every dynamic tool are SENSITIVE and declare `alwaysRequiresApproval()`. You are asked before
**every** call, even with the "Never" policy or an "always allow" entry, and the approval dialog shows the source.
**Dynamic code runs in-process without a sandbox.** Only approve code you have read.
The app needs a JDK. The jpackage release runtime includes `jdk.compiler`.

## Skills

Skills are stored as files, one folder per skill, following the common `SKILL.md` layout:

```
~/.harness-exp/skills/
  code-reviewer/SKILL.md
  summarizer/SKILL.md
```

```markdown
---
name: "Code reviewer"
description: "Reviews code snippets"
triggers: ["review","code"]   # or "review, code", or a "- item" list; empty = always injected
enabled: true
---

When asked to review code, list bugs first, then risks, then style nits.
```

- On first start the folder is seeded with the bundled defaults (`src/main/resources/.../skills/defaults`).
  Skills from older `settings.json` files are moved into the folder once.
- The folder is read at startup. Use **⟳ Refresh** in the Skills section to re-read it after editing files by hand.
  Files that can't be read are skipped and listed there.
- The agent can add or update skills with the `create_skill` tool (`name`, `description`, `triggers`,
  `instructions`). A skill with the same name is replaced. The tool is SENSITIVE and needs approval on every call.

## Attachments

Attach files with **📎**, by dropping them on the window, or by pasting copied files (Ctrl+V). They appear as chips
above the input box (✕ removes one), and you can send a message with only attachments. Files are loaded in package
`attachments` and stored with the message, so they show up again when you reopen a conversation or regenerate a
reply.

| Type | Extensions | Sent to the model as |
|---|---|---|
| Markdown / text | md, markdown, txt, log, csv, json, yaml, yml, xml | Text inside `<attachment name=… type=…>` |
| PDF | pdf | Text extracted with PDFBox. Scanned PDFs with no text layer are rejected |
| Pictures | png, jpg, jpeg, gif, bmp, webp | Images in the Ollama request. You need a vision model such as `llava`, `qwen2.5vl` or `gemma3` |
| Audio | mp3, wav, m4a, aac, aif(f), ogg, flac | A note only, because Ollama has no audio input. You can play the file in the chat with ▶ |

The limits are set in the **Context builder** section: files per message, MB per file, and characters of text kept
per file (longer text is cut). Attachment text goes through the input guardrails, so blocked terms block the message,
but the message-length limit doesn't apply to attachments. Click a chip or thumbnail to view the file's text or
the full-size image.

## Export / import

**Export…** and **Import…** in the Harness window move the whole setup between machines as a single JSON file
(`{"format":"harness-exp/settings","version":1,…}`, class `config.SettingsTransfer`).

- **Export** writes the settings as currently shown in the window, including unsaved edits: model, system prompt,
  context, tools and MCP, agent loop, guardrails, approval, reply and storage. You can also include the skills
  folder and the dynamic tools' source code. The API key is left out unless you tick it.
- **Import** accepts an export or a plain `settings.json`, and applies immediately. Each option can be ticked or
  unticked:
  - keep your current API key (on by default);
  - keep your current storage folders (on by default);
  - import skills (same-name skills are replaced);
  - import dynamic tools. This is off by default because the tools are compiled and run as code.

  Items that fail are listed in the import report and the rest are still imported.

## Running

Requirements: Java 17+, Maven, and [Ollama](https://ollama.com) running locally (or an ollama.com API key).

```sh
ollama pull qwen2.5:3b
mvn javafx:run          # run from source
mvn verify              # tests + fat jar in target/
java -jar target/harness-exp-0.1.0-jar-with-dependencies.jar
```

Settings are stored in `~/.harness-exp/settings.json` (override with `-Dharness.settings=<path>`).
Skills, dynamic tools and the history DB default to the same folder (`skills/`, `dynamic-tools/`, `history.db`). Each can be moved to any external location in the **Storage** section, which writes the `storage` block of settings.json. Relative paths are resolved against the settings folder and `~` is your home folder. Changes apply after a restart, and existing data is not moved. Only the default skills ship inside the jar; they are copied into an empty skills folder.

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
