package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Database(
    entities = [PrinterEntity::class, PrintJobEntity::class, DiagnosticLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class InkFlowDatabase : RoomDatabase() {
    abstract fun printerDao(): PrinterDao
    abstract fun printJobDao(): PrintJobDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao

    companion object {
        @Volatile
        private var instance: InkFlowDatabase? = null

        fun getInstance(context: Context): InkFlowDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    InkFlowDatabase::class.java,
                    "inkflow_spooler.db"
                ).build().also { instance = it }
            }
        }
    }
}

private val Context.inkFlowDataStore by preferencesDataStore(name = "inkflow_prefs")

data class InkFlowPreferences(
    val forceWifiSocketBinding: Boolean = true,
    val autoResumeOnWifiReturn: Boolean = true,
    val visualInkCyan: Int = 78,
    val visualInkMagenta: Int = 64,
    val visualInkYellow: Int = 72,
    val visualInkBlack: Int = 85
)

class UserPreferencesStore(private val context: Context) {
    private object Keys {
        val FORCE_WIFI_BINDING = booleanPreferencesKey("force_wifi_binding")
        val AUTO_RESUME_WIFI = booleanPreferencesKey("auto_resume_wifi")
        val VISUAL_INK_CYAN = intPreferencesKey("visual_ink_cyan")
        val VISUAL_INK_MAGENTA = intPreferencesKey("visual_ink_magenta")
        val VISUAL_INK_YELLOW = intPreferencesKey("visual_ink_yellow")
        val VISUAL_INK_BLACK = intPreferencesKey("visual_ink_black")
    }

    val preferences: Flow<InkFlowPreferences> = context.inkFlowDataStore.data.map { prefs ->
        InkFlowPreferences(
            forceWifiSocketBinding = prefs[Keys.FORCE_WIFI_BINDING] ?: true,
            autoResumeOnWifiReturn = prefs[Keys.AUTO_RESUME_WIFI] ?: true,
            visualInkCyan = prefs[Keys.VISUAL_INK_CYAN] ?: 78,
            visualInkMagenta = prefs[Keys.VISUAL_INK_MAGENTA] ?: 64,
            visualInkYellow = prefs[Keys.VISUAL_INK_YELLOW] ?: 72,
            visualInkBlack = prefs[Keys.VISUAL_INK_BLACK] ?: 85
        )
    }

    suspend fun setForceWifiBinding(enabled: Boolean) {
        context.inkFlowDataStore.edit { it[Keys.FORCE_WIFI_BINDING] = enabled }
    }

    suspend fun setAutoResumeOnWifi(enabled: Boolean) {
        context.inkFlowDataStore.edit { it[Keys.AUTO_RESUME_WIFI] = enabled }
    }

    suspend fun setVisualInkLevels(cyan: Int, magenta: Int, yellow: Int, black: Int) {
        context.inkFlowDataStore.edit {
            it[Keys.VISUAL_INK_CYAN] = cyan.coerceIn(0, 100)
            it[Keys.VISUAL_INK_MAGENTA] = magenta.coerceIn(0, 100)
            it[Keys.VISUAL_INK_YELLOW] = yellow.coerceIn(0, 100)
            it[Keys.VISUAL_INK_BLACK] = black.coerceIn(0, 100)
        }
    }
}
