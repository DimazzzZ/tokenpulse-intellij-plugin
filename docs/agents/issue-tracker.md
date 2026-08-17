# Local issue tracker

## Location

Track work as local Markdown files under:

```text
.scratch/<feature>/
```

No external issue-tracker service or CLI is required.

## Conventions

- Use one feature directory per independent effort.
- Keep `tickets.md` in the feature directory as the ordered index of work.
- Store each actionable ticket as its own Markdown file when the effort needs multiple tickets.
- State a ticket's blocking edges explicitly.
- Keep `.scratch/` work local unless a repository maintainer intentionally decides otherwise.