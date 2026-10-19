package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Persists independent autonomous wake plans separately from normal heartbeat settings. */
class HeartbeatScheduleStore(
    context: Context,
    private val assistantId: String? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Entry(val id: String, val plan: HeartbeatSchedulePlan)

    private fun entriesKey(id: String? = assistantId) = "entries_${id ?: "default"}"

    /** Reads multi-task storage, and imports an older single plan when present. */
    fun list(assistant: String? = assistantId): List<Entry> {
        val raw = preferences.getString(entriesKey(assistant), null)
        if (raw != null) {
            return runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())
        }
        val legacy = preferences.getString(planKey(assistant), null)
            ?: if (assistant != null) preferences.getString(PLAN_KEY, null) else null
        val plan = legacy?.let {
            runCatching { json.decodeFromString<HeartbeatSchedulePlan>(it) }.getOrNull()
        }
        return if (plan != null && plan.assistantId == assistant) listOf(Entry("legacy", plan)) else emptyList()
    }

    private fun save(assistant: String?, entries: List<Entry>) {
        preferences.edit()
            .putString(entriesKey(assistant), json.encodeToString(entries))
            .remove(planKey(assistant))
            .apply()
    }

    /** Adds a new independent task without replacing existing plans. */
    fun add(plan: HeartbeatSchedulePlan): String {
        val id = UUID.randomUUID().toString()
        val normalized = plan.copy(wakeAtMillis = plan.wakeAtMillis.distinct().sorted())
        save(plan.assistantId, list(plan.assistantId) + Entry(id, normalized))
        return id
    }

    fun remove(id: String, assistant: String? = assistantId): Boolean {
        val previous = list(assistant)
        val updated = previous.filterNot { it.id == id }
        if (updated.size == previous.size) return false
        save(assistant, updated)
        return true
    }

    /** Compatibility accessor: returns the task with the earliest next wake. */
    fun read(): HeartbeatSchedulePlan? =
        list().minByOrNull { it.plan.wakeAtMillis.minOrNull() ?: Long.MAX_VALUE }?.plan

    /** Compatibility method for legacy callers that intentionally replace all tasks. */
    fun replace(plan: HeartbeatSchedulePlan) {
        save(
            plan.assistantId,
            listOf(Entry(UUID.randomUUID().toString(), plan.copy(wakeAtMillis = plan.wakeAtMillis.distinct().sorted())))
        )
    }

    fun clear() = save(assistantId, emptyList())

    fun readForAssistant(assistantId: String?): HeartbeatSchedulePlan? =
        HeartbeatScheduleStore(appContext, assistantId).read()

    fun nextWakeAtMillis(assistantId: String? = null): Long? =
        list(assistantId).flatMap { it.plan.wakeAtMillis }.minOrNull()

    /** Advances every due task independently; unrelated future tasks are retained. */
    fun consumeDue(assistantId: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val entries = list(assistantId)
        var consumed = false
        val updated = entries.mapNotNull { entry ->
            val plan = entry.plan
            if (plan.wakeAtMillis.none { it <= nowMillis }) return@mapNotNull entry
            consumed = true
            val future = plan.wakeAtMillis.filter { it > nowMillis }
            val next = if (future.isNotEmpty()) future else when {
                plan.repeatIntervalMinutes != null && plan.repeatIntervalMinutes > 0L ->
                    listOf(nowMillis + plan.repeatIntervalMinutes * 60_000L)
                plan.recurrence != null && plan.recurrenceTimeMinutes != null ->
                    listOf(
                        HeartbeatSchedulePlanner.nextCalendarWakeAt(
                            recurrence = plan.recurrence,
                            timeMinutes = plan.recurrenceTimeMinutes,
                            nowMillis = nowMillis,
                        )
                    )
                else -> emptyList()
            }
            if (next.isEmpty()) null else entry.copy(plan = plan.copy(wakeAtMillis = next))
        }
        if (consumed) save(assistantId, updated)
        return consumed
    }

    companion object {
        private const val PREFERENCES_NAME = "personal_heartbeat_schedule"
        private const val PLAN_KEY = "plan"
    }

    private fun planKey(id: String? = assistantId): String =
        id?.let { "${PLAN_KEY}_$it" } ?: PLAN_KEY
}
