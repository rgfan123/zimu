# Domain Docs

## Before exploring

- Read `CONTEXT.md` at the repository root.
- If `CONTEXT-MAP.md` is introduced later, follow it to the relevant context document.
- Read ADRs under `docs/adr/` that touch the area being changed.
- If one of these paths does not exist, proceed silently.

## Layout

This repository is single-context: its glossary is `CONTEXT.md` and system-wide ADRs live under `docs/adr/`.

## Vocabulary and decisions

- Use the domain terms defined in `CONTEXT.md` in tickets, tests, code, and documentation.
- Do not silently introduce synonyms for defined concepts.
- Surface any conflict with an ADR explicitly instead of overriding it.
