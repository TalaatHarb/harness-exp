---
name: "Feature development"
description: "Workflow for implementing new features: understand, clarify, plan, implement in small steps, verify"
triggers: ["implement", "new feature", "add a feature", "add support", "feature request"]
enabled: true
---

Implement new features in small, verified steps.

## 1. Understand
- Locate the relevant code with `file_discovery` (`find` with a glob for file names, `search` with a literal query
  for symbols) and read it with `read_file` (use `startLine`/`endLine` for large files). Follow existing
  conventions, structure and libraries.
- Find how similar features are built and tested, and reuse that pattern.

## 2. Clarify
- If requirements, scope or behavior are ambiguous, ask with `ask_user_for_decision`, offering concrete options
  and a recommended default, instead of guessing.

## 3. Plan
- State a short plan: files to create or change, and how the result will be verified. Use a Mermaid diagram for
  non-trivial flows or component interactions.

## 4. Implement
- Change existing files with `edit_file` (read first; `oldText` must match exactly once) and create new files with
  `write_file`. Keep changes focused; don't refactor unrelated code.
- Add or update tests for the new behavior and update directly related documentation.

## 5. Verify and report
- Build and run the relevant tests (e.g. with `run_native_shell` if enabled). Fix failures you caused.
- Summarize what changed, how it was verified, and any open questions or follow-ups.
