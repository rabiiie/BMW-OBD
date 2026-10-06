package com.rabie.bmwobd.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Color de acento de la interfaz. Los colores de aviso (ambar, rojo) no cambian con el tema. */
enum class Accent(val title: String, val argb: Long) {
    SPORT("Sport", 0xFFFF6A1F),
    M("M", 0xFF3D9BFF),
    ICE("Hielo", 0xFFDDE3EA),
}

data class AppSettings(
    val sound: Boolean = true,
    val accent: Accent = Accent.SPORT,
    val keepScreenOn: Boolean = true,
    val expertConsole: Boolean = false,
)

/** Los ajustes de la app, guardados en el movil. */
class SettingsStore(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean(KEY_SOUND, next.sound)
            .putString(KEY_ACCENT, next.accent.name)
            .putBoolean(KEY_SCREEN_ON, next.keepScreenOn)
            .putBoolean(KEY_EXPERT, next.expertConsole)
            .apply()
        _state.value = next
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sound = prefs.getBoolean(KEY_SOUND, defaults.sound),
            accent = Accent.entries.firstOrNull { it.name == prefs.getString(KEY_ACCENT, null) } ?: defaults.accent,
            keepScreenOn = prefs.getBoolean(KEY_SCREEN_ON, defaults.keepScreenOn),
            expertConsole = prefs.getBoolean(KEY_EXPERT, defaults.expertConsole),
        )
    }

    private companion object {
        const val KEY_SOUND = "sound"
        const val KEY_ACCENT = "accent"
        const val KEY_SCREEN_ON = "screen_on"
        const val KEY_EXPERT = "expert_console"
    }
}
