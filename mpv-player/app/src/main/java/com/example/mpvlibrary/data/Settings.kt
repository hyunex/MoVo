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
 * Options for continue-watching playback playlist generation.
 */
enum class ContinuePlaylistMode(val value: String, val title: String, val subtitle: String) {
    ORIGINAL_FOLDER("folder", "원본 동영상 폴더", "같은 폴더(동일 디렉터리 경로)의 영상들을 이름순으로 재생 (기본값)"),
    CONTINUE_LIST("continue_list", "이어보기 목록", "화면에 표시된 이어보기 영상들을 순서대로 재생");

    companion object {
        fun fromValue(v: String?): ContinuePlaylistMode = entries.find { it.value == v } ?: ORIGINAL_FOLDER
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
        val KEY_SPEED_STEP = doublePreferencesKey("speed_step")
        val KEY_CONTINUE_PLAYLIST = stringPreferencesKey("continue_playlist_source")
        val KEY_VIDEO_ALIGN_Y = stringPreferencesKey("video_align_y")
        val KEY_THRESHOLD = doublePreferencesKey("watched_threshold")
        val KEY_AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val KEY_MPV_OPTIONS = stringPreferencesKey("mpv_options")
        val KEY_TAP_SEEK = doublePreferencesKey("tap_seek_sec")
        val KEY_FAST_SPEED = doublePreferencesKey("fast_speed")
        val KEY_REMEMBER_BRIGHT = booleanPreferencesKey("remember_brightness")
        val KEY_SAVED_BRIGHT = doublePreferencesKey("saved_brightness")
        val KEY_AUTO_SUB = booleanPreferencesKey("auto_subtitle")
        val KEY_SUB_FONT_SIZE = doublePreferencesKey("sub_font_size")
        val KEY_SUB_COLOR = stringPreferencesKey("sub_color")
        val KEY_THUMB_SCALE = stringPreferencesKey("thumb_scale")

        const val DEFAULT_SUB_FONT_SIZE = 55.0
        const val DEFAULT_SUB_COLOR = "#FFFFFF"
        val SUB_COLOR_PRESETS = listOf("#FFFFFF", "#FFFF00", "#00FFFF", "#00FF00", "#FF80C0")

        val DEFAULT_SPEED_PRESETS = listOf(0.5, 0.75, 1.0, 1.2, 1.25, 1.5, 1.75, 2.0)
        const val DEFAULT_SPEED_STEP = 0.05
        const val DEFAULT_CONTINUE_PLAYLIST = "folder"
        const val DEFAULT_VIDEO_ALIGN_Y = "-1"
        const val DEFAULT_MPV_OPTIONS = ""

        fun parseSpeedPresets(raw: String?): List<Double> {
            if (raw.isNullOrBlank()) return DEFAULT_SPEED_PRESETS
            val tokens = raw.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
            val list = tokens
                .mapNotNull { it.toDoubleOrNull() }
                .filter { it.isFinite() && it in 0.1..5.0 }
                .map { Math.round(it * 100.0) / 100.0 } // 2 decimals
                .distinct()
                .sorted()
            return list.ifEmpty { DEFAULT_SPEED_PRESETS }
        }

        /**
         * Validates bulk preset input supporting comma/whitespace/newline separation.
         * Rejects any invalid, non-finite, out-of-range (0.1..5.0) or >2 decimal values.
         */
        fun validateAndParseSpeedPresets(raw: String): Result<List<Double>> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("최소 1개 이상의 배속 값을 입력해 주세요."))
            }
            val tokens = trimmed.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                return Result.failure(IllegalArgumentException("최소 1개 이상의 배속 값을 입력해 주세요."))
            }
            val result = mutableListOf<Double>()
            for (token in tokens) {
                val num = token.toDoubleOrNull()
                if (num == null || !num.isFinite()) {
                    return Result.failure(IllegalArgumentException("올바른 숫자가 아닙니다: '$token'"))
                }
                if (num < 0.1 || num > 5.0) {
                    return Result.failure(IllegalArgumentException("배속 범위는 0.1 ~ 5.0 사이여야 합니다: '$token'"))
                }
                val dot = token.indexOf('.')
                if (dot >= 0) {
                    val decPart = token.substring(dot + 1).trimEnd('0')
                    if (decPart.length > 2) {
                        return Result.failure(IllegalArgumentException("소수점은 최대 2자리까지만 지원합니다: '$token'"))
                    }
                }
                if (Math.abs(Math.round(num * 100.0) - num * 100.0) > 1e-5) {
                    return Result.failure(IllegalArgumentException("소수점은 최대 2자리까지만 지원합니다: '$token'"))
                }
                val rounded = Math.round(num * 100.0) / 100.0
                result.add(rounded)
            }
            val distinctSorted = result.distinct().sorted()
            if (distinctSorted.isEmpty()) {
                return Result.failure(IllegalArgumentException("유효한 배속 값이 없습니다."))
            }
            return Result.success(distinctSorted)
        }

        /**
         * Validates configurable speed increment (step) in 0.01..1.0 with max 2 decimals.
         */
        fun validateSpeedStep(raw: String): Result<Double> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("배속 단위를 입력해 주세요."))
            }
            val num = trimmed.toDoubleOrNull()
            if (num == null || !num.isFinite()) {
                return Result.failure(IllegalArgumentException("올바른 숫자가 아닙니다: '$trimmed'"))
            }
            if (num < 0.01 || num > 1.0) {
                return Result.failure(IllegalArgumentException("배속 단위는 0.01 ~ 1.0 사이여야 합니다: '$trimmed'"))
            }
            val dot = trimmed.indexOf('.')
            if (dot >= 0) {
                val decPart = trimmed.substring(dot + 1).trimEnd('0')
                if (decPart.length > 2) {
                    return Result.failure(IllegalArgumentException("소수점은 최대 2자리까지만 지원합니다: '$trimmed'"))
                }
            }
            if (Math.abs(Math.round(num * 100.0) - num * 100.0) > 1e-5) {
                return Result.failure(IllegalArgumentException("소수점은 최대 2자리까지만 지원합니다: '$trimmed'"))
            }
            val rounded = Math.round(num * 100.0) / 100.0
            return Result.success(rounded)
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
                .map {
                    // mpv CLI식 "--vo=gpu" 입력을 내부 정규형("vo")으로 통일해야
                    // 차단 검사와 setOptionString 적용이 같은 키로 동작한다.
                    val rawKey = it.substringBefore('=').trim()
                    val normKey = rawKey.trimStart { c -> c == '-' }.trimStart().lowercase()
                    normKey to it.substringAfter('=').trim()
                }
                .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() && !isBlockedOption(k) }
                .toList()

        private val BLOCKED_EXACT = setOf(
            "o", "config", "config-dir", "include",
            "load-script", "script", "scripts", "script-file",
            "force-window", "idle", "input", "input-conf", "input-commands",
            "input-preprocess-wheel", "osc", "ytdl", "ytdl-path", "ytdl-raw-options",
            "sub-file", "sub-files", "audio-file", "audio-files", "external-file",
            "external-files", "cover-art-file",
        )

        private val BLOCKED_PREFIX = listOf(
            "script", "lua", "js", "javascript", "ytdl",
            "tls", "ssl", "http", "proxy", "cookie", "referrer", "user-agent",
            "screenshot", "watch-later", "write-filename-in-watch-later",
            "record", "stream-record", "stream-dump", "stream-capture", "dump",
            "cache-dir", "cache-on-disk", "gpu-shader-cache-dir", "icc-cache-dir",
            "config", "include", "load-script", "input", "osc", "odash", "ogs",
            "sub-file", "sub-files", "audio-file", "audio-files", "external-file",
            "external-files", "cover-art",
        )

        /** File / script / network exfiltration options must never come from pasted text. */
        fun isBlockedOption(key: String): Boolean {
            // "--vo=gpu"처럼 앞에 붙는 대시를 걷어내야 "--config-dir" 우회가 막힌다.
            var k = key.trim().lowercase()
            if (k.isEmpty()) return true
            while (k.startsWith("-")) k = k.drop(1)
            // "--" 내부 공백 표기("-- config-dir")도 동일하게 봉쇄.
            k = k.trimStart()
            if (k.isEmpty() || k in BLOCKED_EXACT) return true
            return BLOCKED_PREFIX.any { k == it || k.startsWith(it) }
        }

        /** Keys dropped by [parseOptions] so callers can warn instead of failing silently. */
        fun blockedOptions(raw: String): List<String> =
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .map { it.substringBefore('=').trim() }
                .filter { isBlockedOption(it) }
                .toList()
    }

    val defaultSpeed: Flow<Double> = context.dataStore.data.map { it[KEY_SPEED] ?: 1.0 }
    val speedPresets: Flow<List<Double>> = context.dataStore.data.map {
        parseSpeedPresets(it[KEY_SPEED_PRESETS])
    }
    val videoAlignY: Flow<String> = context.dataStore.data.map {
        val v = it[KEY_VIDEO_ALIGN_Y] ?: DEFAULT_VIDEO_ALIGN_Y
        if (v == "-1" || v == "0" || v == "1") v else DEFAULT_VIDEO_ALIGN_Y
    }
    suspend fun setDefaultSpeed(v: Double) {
        context.dataStore.edit { it[KEY_SPEED] = v.coerceIn(0.1, 5.0) }
    }
    suspend fun setSpeedPresets(presets: List<Double>) {
        context.dataStore.edit { it[KEY_SPEED_PRESETS] = formatSpeedPresets(presets) }
    }
    val speedStep: Flow<Double> = context.dataStore.data.map { it[KEY_SPEED_STEP] ?: DEFAULT_SPEED_STEP }
    suspend fun setSpeedStep(v: Double) {
        if (!v.isFinite()) return
        val rounded = (Math.round(v.coerceIn(0.01, 1.0) * 100.0)) / 100.0
        context.dataStore.edit { it[KEY_SPEED_STEP] = rounded }
    }
    val continuePlaylistMode: Flow<String> = context.dataStore.data.map {
        it[KEY_CONTINUE_PLAYLIST] ?: DEFAULT_CONTINUE_PLAYLIST
    }
    suspend fun setContinuePlaylistMode(v: String) {
        val valid = ContinuePlaylistMode.fromValue(v).value
        context.dataStore.edit { it[KEY_CONTINUE_PLAYLIST] = valid }
    }
    suspend fun setVideoAlignY(v: String) {
        // 허용값 외 입력은 mpv 주입이 아닌 TOP으로 정규화.
        val ok = v == "-1" || v == "0" || v == "1"
        context.dataStore.edit { it[KEY_VIDEO_ALIGN_Y] = if (ok) v else DEFAULT_VIDEO_ALIGN_Y }
    }
    val watchedThreshold: Flow<Double> = context.dataStore.data.map { it[KEY_THRESHOLD] ?: 0.9 }
    val autoAdvance: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_ADVANCE] ?: false }
    val mpvOptionsRaw: Flow<String> =
        context.dataStore.data.map { it[KEY_MPV_OPTIONS] ?: DEFAULT_MPV_OPTIONS }
    suspend fun setThreshold(v: Double) { context.dataStore.edit { it[KEY_THRESHOLD] = v.coerceIn(0.5, 0.99) } }
    suspend fun setAutoAdvance(v: Boolean) { context.dataStore.edit { it[KEY_AUTO_ADVANCE] = v } }
    suspend fun setMpvOptions(raw: String) {
        // DataStore 적재 전에 차단 키를 제거해 저장 → 적용 경로(parseOptions) 간 drift를 없앤다.
        val sanitized = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) return@filter true
                !isBlockedOption(t.substringBefore('=').trim())
            }
            .joinToString("\n").take(8000)
        context.dataStore.edit { it[KEY_MPV_OPTIONS] = sanitized }
    }
    // P0: gesture & convenience settings
    val tapSeekSec: Flow<Double> = context.dataStore.data.map { it[KEY_TAP_SEEK] ?: 10.0 }
    val fastSpeed: Flow<Double> = context.dataStore.data.map { it[KEY_FAST_SPEED] ?: 2.0 }
    val rememberBrightness: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMEMBER_BRIGHT] ?: false }
    val savedBrightness: Flow<Double> = context.dataStore.data.map { it[KEY_SAVED_BRIGHT] ?: -1.0 }
    val autoSubtitle: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_SUB] ?: true }
    val subFontSize: Flow<Double> = context.dataStore.data.map { it[KEY_SUB_FONT_SIZE] ?: DEFAULT_SUB_FONT_SIZE }
    val subColor: Flow<String> = context.dataStore.data.map { it[KEY_SUB_COLOR] ?: DEFAULT_SUB_COLOR }

    suspend fun setSubFontSize(v: Double) { context.dataStore.edit { it[KEY_SUB_FONT_SIZE] = v.coerceIn(10.0, 200.0) } }
    suspend fun setSubColor(v: String) {
        // UI는 프리셋만 노출하지만 DataStore 경계에서 한 번 더 검증: mpv sub-color 파서 주입 방지.
        val ok = v.matches(Regex("#[0-9A-Fa-f]{6}"))
        context.dataStore.edit { it[KEY_SUB_COLOR] = if (ok) v.uppercase() else DEFAULT_SUB_COLOR }
    }

    suspend fun setTapSeekSec(v: Double) { context.dataStore.edit { it[KEY_TAP_SEEK] = v.coerceIn(1.0, 60.0) } }
    suspend fun setFastSpeed(v: Double) { context.dataStore.edit { it[KEY_FAST_SPEED] = v.coerceIn(0.5, 5.0) } }
    suspend fun setRememberBrightness(v: Boolean) { context.dataStore.edit { it[KEY_REMEMBER_BRIGHT] = v } }
    suspend fun setSavedBrightness(v: Double) { context.dataStore.edit { it[KEY_SAVED_BRIGHT] = v } }
    suspend fun setAutoSubtitle(v: Boolean) { context.dataStore.edit { it[KEY_AUTO_SUB] = v } }
    val thumbScale: Flow<String> = context.dataStore.data.map { it[KEY_THUMB_SCALE] ?: "medium" }

    suspend fun setThumbScale(v: String) {
        context.dataStore.edit { it[KEY_THUMB_SCALE] = if (v in setOf("small", "medium", "large")) v else "medium" }
    }
}
