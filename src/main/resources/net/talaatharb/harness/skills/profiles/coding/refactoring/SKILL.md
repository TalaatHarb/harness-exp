---
name: "Refactoring"
description: "Workflow for behavior-preserving refactoring of existing code"
triggers: ["refactor", "rename", "extract", "clean up", "cleanup", "restructure", "simplify", "code smell"]
enabled: true
---

Refactor existing code without changing its observable behavior.

## 1. Scope and safety net
- Identify the code to change and every usage: use `file_discovery` `search` with the symbol as a literal query
  (it also finds strings, configuration and documentation) and read the relevant ranges with `read_file`.
- Confirm tests cover the behavior. If they don't, propose adding characterization tests first.
- If the goal or scope is unclear (which smell, how far to go, public API changes), ask with
  `ask_user_for_decision`.

## 2. Plan
- Name the refactoring (rename, extract method/class, inline, move, introduce parameter object, replace
  conditional with polymorphism, remove duplication, ...) and list the affected files. Use a Mermaid class or flow
  diagram for structural changes.

## 3. Apply in small steps
- One refactoring at a time with `edit_file`; each `oldText` must match exactly once, so include enough context.
- Update all references found in step 1. Text replacement is not semantic: check overloads, shadowed names,
  reflection, serialization names and public APIs by hand.
- Don't mix behavior changes or unrelated fixes into a refactoring.

## 4. Verify and report
- Build and run the tests after each meaningful step (e.g. with `run_native_shell` if enabled).
- Summarize the refactorings applied, files touched, verification results and any remaining risks.
