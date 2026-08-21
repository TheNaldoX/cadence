package fr.velo.cadence.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.velo.cadence.model.RiderLevel
import fr.velo.cadence.model.RiderProfile
import fr.velo.cadence.model.RoadStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cadence_prefs")

class UserPreferences(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("display_name")
        val LEVEL = stringPreferencesKey("level")
        val RIDER_WEIGHT = doublePreferencesKey("rider_weight")
        val BIKE_WEIGHT = doublePreferencesKey("bike_weight")
        val MAX_HR = intPreferencesKey("max_hr")
        val REST_HR = intPreferencesKey("rest_hr")
        val FTP = intPreferencesKey("ftp")
        val ROAD_STYLE = stringPreferencesKey("road_style")
        val AUTO_LEVEL = booleanPreferencesKey("auto_level")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BAROMETER = booleanPreferencesKey("use_barometer")
        val HR_SENSOR = stringPreferencesKey("hr_sensor_address")
        val POWER_SENSOR = stringPreferencesKey("power_sensor_address")
        val CSC_SENSOR = stringPreferencesKey("csc_sensor_address")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val profile: Flow<RiderProfile> = context.dataStore.data.map { p ->
        RiderProfile(
            displayName = p[Keys.NAME] ?: "",
            level = RiderLevel.fromName(p[Keys.LEVEL]),
            riderWeightKg = p[Keys.RIDER_WEIGHT] ?: 72.0,
            bikeWeightKg = p[Keys.BIKE_WEIGHT] ?: 8.5,
            maxHeartRate = p[Keys.MAX_HR] ?: 190,
            restingHeartRate = p[Keys.REST_HR] ?: 55,
            ftpWatts = p[Keys.FTP] ?: 0,
            preferredRoadStyle = RoadStyle.fromName(p[Keys.ROAD_STYLE]),
            autoLevelFromHistory = p[Keys.AUTO_LEVEL] ?: true,
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            autoPause = p[Keys.AUTO_PAUSE] ?: true,
            voiceGuidance = p[Keys.VOICE_GUIDANCE] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            useBarometer = p[Keys.BAROMETER] ?: true,
            heartRateSensor = p[Keys.HR_SENSOR],
            powerSensor = p[Keys.POWER_SENSOR],
            cscSensor = p[Keys.CSC_SENSOR],
            exportFormat = p[Keys.EXPORT_FORMAT] ?: "GPX",
            onboarded = p[Keys.ONBOARDED] ?: false,
        )
    }

    suspend fun updateProfile(profile: RiderProfile) {
        context.dataStore.edit { p ->
            p[Keys.NAME] = profile.displayName
            p[Keys.LEVEL] = profile.level.name
            p[Keys.RIDER_WEIGHT] = profile.riderWeightKg
            p[Keys.BIKE_WEIGHT] = profile.bikeWeightKg
            p[Keys.MAX_HR] = profile.maxHeartRate
            p[Keys.REST_HR] = profile.restingHeartRate
            p[Keys.FTP] = profile.ftpWatts
            p[Keys.ROAD_STYLE] = profile.preferredRoadStyle.name
            p[Keys.AUTO_LEVEL] = profile.autoLevelFromHistory
        }
    }

    suspend fun setLevel(level: RiderLevel) {
        context.dataStore.edit { it[Keys.LEVEL] = level.name }
    }

    suspend fun setAutoPause(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PAUSE] = enabled }
    }

    suspend fun setVoiceGuidance(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOICE_GUIDANCE] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setUseBarometer(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BAROMETER] = enabled }
    }

    suspend fun setSensorAddress(kind: SensorKind, address: String?) {
        val key = when (kind) {
            SensorKind.HEART_RATE -> Keys.HR_SENSOR
            SensorKind.POWER -> Keys.POWER_SENSOR
            SensorKind.SPEED_CADENCE -> Keys.CSC_SENSOR
        }
        context.dataStore.edit { p ->
            if (address == null) p.remove(key) else p[key] = address
        }
    }

    suspend fun setExportFormat(format: String) {
        context.dataStore.edit { it[Keys.EXPORT_FORMAT] = format }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = value }
    }
}

data class AppSettings(
    val autoPause: Boolean = true,
    val voiceGuidance: Boolean = true,
    val keepScreenOn: Boolean = true,
    val useBarometer: Boolean = true,
    val heartRateSensor: String? = null,
    val powerSensor: String? = null,
    val cscSensor: String? = null,
    val exportFormat: String = "GPX",
    val onboarded: Boolean = false,
)

enum class SensorKind { HEART_RATE, POWER, SPEED_CADENCE }
