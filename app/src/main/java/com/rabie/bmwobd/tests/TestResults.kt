package com.rabie.bmwobd.tests

import android.content.SharedPreferences
import com.rabie.bmwobd.obd.FanTest
import com.rabie.bmwobd.obd.TestOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La ultima vez que se hizo una prueba: cuando, como acabo, el mensaje, cada paso en una linea y
 * si quien la hizo oyo o vio moverse la pieza (null mientras no lo diga).
 */
data class TestRecord(
    val atMillis: Long,
    val outcome: TestOutcome,
    val message: String,
    val steps: List<String>,
    val heard: Boolean? = null,
) {
    fun encode(): String = listOf(
        atMillis.toString(),
        outcome.name,
        message,
        steps.joinToString(STEP_SEPARATOR),
        when (heard) {
            true -> "1"
            false -> "0"
            null -> "-"
        },
    ).joinToString(FIELD_SEPARATOR)

    companion object {
        private const val FIELD_SEPARATOR = "\u001F"
        private const val STEP_SEPARATOR = "\u001E"
        private const val FIELDS = 5

        fun of(test: FanTest, message: String, atMillis: Long): TestRecord = TestRecord(
            atMillis,
            TestOutcome.of(test),
            message,
            test.steps.map { "${it.label}: ${it.meaning} (${it.query} → ${it.response ?: "-"})" },
        )

        fun decode(text: String): TestRecord? {
            val parts = text.split(FIELD_SEPARATOR)
            if (parts.size != FIELDS) return null
            return TestRecord(
                atMillis = parts[0].toLongOrNull() ?: return null,
                outcome = TestOutcome.entries.firstOrNull { it.name == parts[1] } ?: return null,
                message = parts[2],
                steps = parts[3].split(STEP_SEPARATOR).filter { it.isNotEmpty() },
                heard = when (parts[4]) {
                    "1" -> true
                    "0" -> false
                    else -> null
                },
            )
        }
    }
}

/** Las ultimas pruebas, guardadas en el movil por la clave de cada prueba. */
class TestResultStore(private val prefs: SharedPreferences) {

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, TestRecord>> = _records.asStateFlow()

    fun save(key: String, record: TestRecord) {
        prefs.edit().putString(key, record.encode()).apply()
        _records.value = _records.value + (key to record)
    }

    fun setHeard(key: String, heard: Boolean) {
        val record = _records.value[key] ?: return
        save(key, record.copy(heard = heard))
    }

    private fun load(): Map<String, TestRecord> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let(TestRecord::decode)?.let { key to it } }.toMap()

    companion object {
        fun actuatorKey(lid: Int) = "steller_%02X".format(lid)
        fun routineKey(name: String) = "servicio_$name"
    }
}
