# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`
- Triage state is recorded as a `Status:` line near the top of each issue file
- Comments and conversation history append under a `## Comments` heading

## Skill operations

- To publish work, create a file under `.scratch/<feature-slug>/`.
- To fetch a ticket, read the referenced markdown file.
- Wayfinder maps use `.scratch/<effort>/map.md` and child tickets use `.scratch/<effort>/issues/NN-<slug>.md`.
- A child ticket records `Type:`, `Status:`, and optional `Blocked by:` lines.
- Claim work by setting `Status: claimed` before implementation.
- Resolve work by appending an `## Answer`, setting `Status: resolved`, and updating the map when one exists.
