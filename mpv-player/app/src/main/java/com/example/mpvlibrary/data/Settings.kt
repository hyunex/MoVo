package com.example.mpvlibrary.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Natural language options for video vertical alignment.
 */
enum class VideoAlign(val value: String, val title: String, val subtitle: String) {
    TOP("-1", "상단 (위쪽 정렬)", "폴더블 내부 화면이나 한손 조작에 최적화된 상단 배치 (추천)"),
    CENTER("0", "중앙 (가운데 정렬)", "화면 정중앙에 영상 배치 (표준 기본값)"),
    BOTTOM("1", "하단 (아래쪽 정렬)", "화면 하단에 영상 배치");

    companion object {
        fun fromValue(v: String): VideoAlign = entries.find { it.value == v } ?: TOP
    }
}

/**
 * Persistent user settings:
 * - default playback speed & customizable speed presets list
 * - natural language video alignment (video-align-y)
 * - watched threshold & auto-advance
 * - advanced raw MPV options (key=value)
 */
class SettingsRepo(private val context: Context) {

    companion object {
        val KEY_SPEED = doublePreferencesKey("default_speed")
        val KEY_SPEED_PRESETS = stringPreferencesKey("speed_presets")
        val KEY_VIDEO_ALIGN_Y = stringPreferencesKey("video_align_y")
        val KEY_THRESHOLD = doublePreferencesKey("watched_threshold")
        val KEY_AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val KEY_MPV_OPTIONS = stringPreferencesKey("mpv_options")

        val DEFAULT_SPEED_PRESETS = listOf(0.5, 0.75, 1.0, 1.2, 1.25, 1.5, 1.75, 2.0)
        const val DEFAULT_VIDEO_ALIGN_Y = "-1"
        const val DEFAULT_MPV_OPTIONS = ""

        fun parseSpeedPresets(raw: String?): List<Double> {
            if (raw.isNullOrBlank()) return DEFAULT_SPEED_PRESETS
            val list = raw.split(",")
                .mapNotNull { it.trim().toDoubleOrNull() }
                .filter { it in 0.1..5.0 }
                .map { (it * 100.0).toInt() / 100.0 } // 2 decimals
                .distinct()
                .sorted()
            return list.ifEmpty { DEFAULT_SPEED_PRESETS }
        }

        fun formatSpeedPresets(list: List<Double>): String =
            list.distinct().sorted().joinToString(",") {
                if (it % 1.0 == 0.0) it.toInt().toString() else "%.2f".format(Locale.US, it).trimEnd('0').trimEnd('.')
            }

        /** Parse "key=value" lines, ignoring blanks and comments. */
        fun parseOptions(raw: String): List<Pair<String, String>> =
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                .toList()
    }

    val defaultSpeed: Flow<Double> = context.dataStore.data.map { it[KEY_SPEED] ?: 1.0 }
    val speedPresets: Flow<List<Double>> = context.dataStore.data.map {
        parseSpeedPresets(it[KEY_SPEED_PRESETS])
    }
    val videoAlignY: Flow<String> = context.dataStore.data.map {
        it[KEY_VIDEO_ALIGN_Y] ?: DEFAULT_VIDEO_ALIGN_Y
    }
    val watchedThreshold: Flow<Double> = context.dataStore.data.map { it[KEY_THRESHOLD] ?: 0.9 }
    val autoAdvance: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_ADVANCE] ?: false }
    val mpvOptionsRaw: Flow<String> =
        context.dataStore.data.map { it[KEY_MPV_OPTIONS] ?: DEFAULT_MPV_OPTIONS }

    suspend fun setDefaultSpeed(v: Double) { context.dataStore.edit { it[KEY_SPEED] = v } }
    suspend fun setSpeedPresets(presets: List<Double>) {
        context.dataStore.edit { it[KEY_SPEED_PRESETS] = formatSpeedPresets(presets) }
    }
    suspend fun setVideoAlignY(v: String) { context.dataStore.edit { it[KEY_VIDEO_ALIGN_Y] = v } }
    suspend fun setThreshold(v: Double) { context.dataStore.edit { it[KEY_THRESHOLD] = v } }
    suspend fun setAutoAdvance(v: Boolean) { context.dataStore.edit { it[KEY_AUTO_ADVANCE] = v } }
    suspend fun setMpvOptions(raw: String) { context.dataStore.edit { it[KEY_MPV_OPTIONS] = raw } }
}
