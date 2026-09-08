package com.thsvkd.curfew.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ChartMode { LINE, BAR }

/** 시각은 자정 기준 분으로 둔다. 0 이상 1439 이하. */
data class CurfewSettings(
    val startMinutes: Int = 120,
    val endMinutes: Int = 420,
    val toleranceSeconds: Int = 300,
    val chartMode: ChartMode = ChartMode.LINE,
)

class SettingsStore(private val context: Context) {

    val flow: Flow<CurfewSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun setCurfew(startMinutes: Int, endMinutes: Int) {
        context.settingsStore.edit {
            it[KEY_START] = startMinutes.coerceIn(0, 1439)
            it[KEY_END] = endMinutes.coerceIn(0, 1439)
        }
    }

    suspend fun setToleranceSeconds(seconds: Int) {
        context.settingsStore.edit { it[KEY_TOLERANCE] = seconds.coerceIn(0, 30 * 60) }
    }

    suspend fun setChartMode(mode: ChartMode) {
        context.settingsStore.edit { it[KEY_CHART] = mode.name }
    }

    private fun Preferences.toSettings(): CurfewSettings {
        val default = CurfewSettings()
        return CurfewSettings(
            startMinutes = this[KEY_START] ?: default.startMinutes,
            endMinutes = this[KEY_END] ?: default.endMinutes,
            toleranceSeconds = this[KEY_TOLERANCE] ?: default.toleranceSeconds,
            chartMode = runCatching { ChartMode.valueOf(this[KEY_CHART] ?: "") }
                .getOrDefault(default.chartMode),
        )
    }

    private companion object {
        val KEY_START = intPreferencesKey("curfewStartMinutes")
        val KEY_END = intPreferencesKey("curfewEndMinutes")
        val KEY_TOLERANCE = intPreferencesKey("toleranceSeconds")
        val KEY_CHART = stringPreferencesKey("chartMode")
    }
}
