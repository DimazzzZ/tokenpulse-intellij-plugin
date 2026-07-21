package org.zhavoronkov.tokenpulse.utils

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Formats an ISO-8601 reset instant string as a short, relative-aware local
 * clock time for tooltip display.
 *
 * Returns `null` when the input is null, blank, or unparseable. Callers must
 * treat `null` as "omit this reset text" — the formatter never returns the
 * original string, so a parse failure cannot leak a raw ISO value into the UI.
 *
 * Output shape (24h, `Locale.ROOT`, [ZoneId.systemDefault]):
 * - same local day as [now] → `"Today HH:mm"`
 * - next local day → `"Tomorrow HH:mm"`
 * - 2..6 days ahead → `"<EEE> HH:mm"` (e.g. `"Wed 09:00"`)
 * - everything else (further out, or already past) → `"MMM d, HH:mm"`
 */
object ResetTimeFormatter {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT)
    private val ABSOLUTE = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT)

    fun formatReset(
        iso: String?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val trimmed = iso?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val instant = parseInstant(trimmed) ?: return null

        val target = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val daysAhead = ChronoUnit.DAYS.between(today, target.toLocalDate())

        return when {
            daysAhead == 0L -> "Today " + TIME.format(target)
            daysAhead == 1L -> "Tomorrow " + TIME.format(target)
            daysAhead in 2L..6L -> WEEKDAY.format(target)
            else -> ABSOLUTE.format(target)
        }
    }

    /**
     * Parse [text] as an [Instant] tolerantly. Accepts:
     * - `Instant` (`...Z`) form,
     * - `OffsetDateTime` (`...+02:00`) form,
     * - `ZonedDateTime` (`...[Europe/Berlin]`) form.
     * Returns `null` on any parse failure.
     */
    private fun parseInstant(text: String): Instant? {
        return try {
            Instant.parse(text)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: Exception) {
                try {
                    ZonedDateTime.parse(text).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
