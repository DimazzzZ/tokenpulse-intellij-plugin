package org.zhavoronkov.tokenpulse.ui

/**
 * A single Claude Code account the user chose to add, produced by the inline
 * discovery checklist in [AccountEditDialog] and consumed by
 * [TokenPulseConfigurable] to create one account row per selection.
 *
 * @property configDir The `CLAUDE_CONFIG_DIR` string, or `null` for the default
 *   dir (`~/.claude`).
 * @property isDefault True when [configDir] represents the default dir.
 * @property label User-facing label (email/org, else displayName, else the
 *   config dir basename).
 */
data class SelectedClaudeAccount(
    val configDir: String?,
    val isDefault: Boolean,
    val label: String,
)
