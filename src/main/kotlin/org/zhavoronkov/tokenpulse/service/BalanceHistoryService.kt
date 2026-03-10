package org.zhavoronkov.tokenpulse.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.zhavoronkov.tokenpulse.model.BalanceHistoryEntry
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.TimeRange
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for persisting and retrieving balance history for charting.
 *
 * Stores history entries in a JSON file and tracks maximum seen balances
 * for percentage normalization.
 */
@Service(Service.Level.APP)
class BalanceHistoryService {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    /** In-memory cache of history entries per account */
    private val historyCache = ConcurrentHashMap<String, MutableList<HistoryRecord>>()

    /** Maximum balance ever seen per account (for percentage calculation) */
    private val maxSeenBalances = ConcurrentHashMap<String, BigDecimal>()

    /** File for persisting history data */
    private val historyFile: File
        get() = File(PathManager.getConfigPath(), "tokenpulse/balance_history.json")

    /** File for persisting max seen balances */
    private val maxBalancesFile: File
        get() = File(PathManager.getConfigPath(), "tokenpulse/max_balances.json")

    init {
        loadFromDisk()
    }

    /**
     * Records a balance snapshot to history.
     *
     * @param snapshot The balance snapshot to record.
     */
    fun recordSnapshot(snapshot: BalanceSnapshot) {
        // Update max seen balance for dollar-based accounts
        val remaining = snapshot.balance.credits?.remaining
        if (remaining != null) {
            val currentMax = maxSeenBalances[snapshot.accountId]
            if (currentMax == null || remaining > currentMax) {
                maxSeenBalances[snapshot.accountId] = remaining
            }
        }

        // Convert to history entry with percentage
        val maxSeen = maxSeenBalances[snapshot.accountId]
        val entry = BalanceHistoryEntry.fromSnapshot(snapshot, maxSeen)

        // Store in cache
        val accountHistory = historyCache.getOrPut(snapshot.accountId) { mutableListOf() }

        // Avoid duplicate entries within the same minute
        val lastEntry = accountHistory.lastOrNull()
        if (lastEntry != null) {
            val timeDiff = entry.timestamp.epochSecond - lastEntry.timestamp
            if (timeDiff < 60) {
                // Update the last entry instead of adding a new one
                accountHistory[accountHistory.size - 1] = entry.toRecord()
                saveToDisk()
                return
            }
        }

        accountHistory.add(entry.toRecord())

        // Prune old entries (keep last 30 days max)
        pruneOldEntries(snapshot.accountId)

        saveToDisk()
    }

    /**
     * Gets history entries for a specific account within the time range.
     *
     * @param accountId The account to get history for.
     * @param timeRange The time range to filter by.
     * @return List of history entries sorted by timestamp.
     */
    fun getHistory(accountId: String, timeRange: TimeRange): List<BalanceHistoryEntry> {
        val startTime = timeRange.getStartInstant()
        val records = historyCache[accountId] ?: return emptyList()

        return records
            .filter { Instant.ofEpochSecond(it.timestamp) >= startTime }
            .map { it.toEntry(accountId) }
            .sortedBy { it.timestamp }
    }

    /**
     * Gets history entries for all accounts within the time range.
     *
     * @param timeRange The time range to filter by.
     * @return Map of accountId to list of history entries.
     */
    fun getAllHistory(timeRange: TimeRange): Map<String, List<BalanceHistoryEntry>> {
        val startTime = timeRange.getStartInstant()

        return historyCache.mapValues { (accountId, records) ->
            records
                .filter { Instant.ofEpochSecond(it.timestamp) >= startTime }
                .map { it.toEntry(accountId) }
                .sortedBy { it.timestamp }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * Gets the maximum seen balance for an account.
     */
    fun getMaxSeenBalance(accountId: String): BigDecimal? {
        return maxSeenBalances[accountId]
    }

    /**
     * Clears all history data.
     */
    fun clearAllHistory() {
        historyCache.clear()
        maxSeenBalances.clear()
        saveToDisk()
    }

    /**
     * Clears history for a specific account.
     */
    fun clearAccountHistory(accountId: String) {
        historyCache.remove(accountId)
        maxSeenBalances.remove(accountId)
        saveToDisk()
    }

    private fun pruneOldEntries(accountId: String) {
        val records = historyCache[accountId] ?: return
        val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 3600).epochSecond

        val pruned = records.filter { it.timestamp >= thirtyDaysAgo }
        if (pruned.size < records.size) {
            historyCache[accountId] = pruned.toMutableList()
        }
    }

    private fun loadFromDisk() {
        try {
            // Load history
            if (historyFile.exists()) {
                val json = historyFile.readText()
                val type = object : TypeToken<Map<String, List<HistoryRecord>>>() {}.type
                val loaded: Map<String, List<HistoryRecord>> = gson.fromJson(json, type) ?: emptyMap()
                historyCache.clear()
                loaded.forEach { (accountId, records) ->
                    historyCache[accountId] = records.toMutableList()
                }
            }

            // Load max balances
            if (maxBalancesFile.exists()) {
                val json = maxBalancesFile.readText()
                val type = object : TypeToken<Map<String, String>>() {}.type
                val loaded: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
                maxSeenBalances.clear()
                loaded.forEach { (accountId, value) ->
                    maxSeenBalances[accountId] = BigDecimal(value)
                }
            }

            TokenPulseLogger.Service.debug("Loaded balance history: ${historyCache.size} accounts")
        } catch (e: Exception) {
            TokenPulseLogger.Service.warn("Failed to load balance history: ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            // Ensure directory exists
            historyFile.parentFile?.mkdirs()

            // Save history
            val historyJson = gson.toJson(historyCache)
            historyFile.writeText(historyJson)

            // Save max balances (convert BigDecimal to String for JSON)
            val maxBalancesMap = maxSeenBalances.mapValues { it.value.toPlainString() }
            val maxBalancesJson = gson.toJson(maxBalancesMap)
            maxBalancesFile.writeText(maxBalancesJson)
        } catch (e: Exception) {
            TokenPulseLogger.Service.warn("Failed to save balance history: ${e.message}")
        }
    }

    /**
     * Compact record for JSON storage (without accountId to reduce redundancy).
     */
    private data class HistoryRecord(
        val timestamp: Long,
        val percentageRemaining: Double,
        val rawValue: String,
        val rawUnit: String
    ) {
        fun toEntry(accountId: String): BalanceHistoryEntry {
            return BalanceHistoryEntry(
                accountId = accountId,
                timestamp = Instant.ofEpochSecond(timestamp),
                percentageRemaining = percentageRemaining,
                rawValue = rawValue,
                rawUnit = rawUnit
            )
        }
    }

    private fun BalanceHistoryEntry.toRecord(): HistoryRecord {
        return HistoryRecord(
            timestamp = timestamp.epochSecond,
            percentageRemaining = percentageRemaining,
            rawValue = rawValue,
            rawUnit = rawUnit
        )
    }

    companion object {
        fun getInstance(): BalanceHistoryService = service()
    }
}
