# Label vocabulary

All labels are namespaced. Every PR gets exactly one `type:` label and 0–2
`domain:` labels. Issues additionally get one triage label and, when relevant,
one `priority:` label.

## Triage (issues)

Use these exact labels when triaging incoming issues:

| Canonical role | Label | Meaning |
| --- | --- | --- |
| Needs evaluation | `needs-triage` | A maintainer needs to evaluate the issue. |
| Waiting on reporter | `needs-info` | More information is needed from the reporter. |
| Ready for agent | `ready-for-agent` | Fully specified and ready for an AFK coding agent. |
| Ready for human | `ready-for-human` | Requires human implementation or intervention. |
| Will not fix | `wontfix` | Will not be actioned. |

## Priority (optional, issues)

| Label | Meaning |
| --- | --- |
| `priority: critical` | Blocks release or breaks production. |
| `priority: high` | Impacts users; should ship soon. |
| `priority: low` | Nice-to-have; can be deferred. |

## Type (required on every PR and issue)

| Label | Meaning |
| --- | --- |
| `type: feature` | New feature or enhancement. |
| `type: bug` | Defect or unexpected behavior. |
| `type: refactor` | Code quality change with no behavior change. |
| `type: docs` | Documentation only. |
| `type: chore` | CI, build, deps, config. |

## Domain (optional, may combine)

| Label | Scope |
| --- | --- |
| `domain: provider` | AI provider integrations. |
| `domain: ui` | UI/UX (status bar, dialogs, tooltips). |
| `domain: settings` | Plugin configuration. |
| `domain: security` | Credentials, PasswordSafe, OAuth. |
| `domain: service` | Background services, refresh, caching. |
| `domain: infra` | CI/CD, build, gradle. |

Do not invent alternate labels unless this mapping is deliberately updated.
