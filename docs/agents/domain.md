# Domain documentation layout

## Layout

This repository uses a single domain context:

- `CONTEXT.md` at the repository root is the domain glossary and current domain-model reference.
- `docs/adr/` contains Architecture Decision Records (ADRs).

## Consumer rules

Before designing, debugging, or implementing a change:

1. Read root `CONTEXT.md` when it exists.
2. Read ADRs in `docs/adr/` relevant to the affected module or decision.
3. Treat existing glossary definitions and accepted ADRs as constraints.
4. When a hard-to-reverse architectural or domain decision is made, record an ADR.
5. Update `CONTEXT.md` when domain vocabulary or a settled business rule changes.

There is no `CONTEXT-MAP.md` because this is not a multi-context repository.