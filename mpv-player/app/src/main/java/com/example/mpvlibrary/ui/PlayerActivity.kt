package com.example.mpvlibrary.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.mpvlibrary.data.AppDb
import com.example.mpvlibrary.data.AppLog
import com.example.mpvlibrary.data.SettingsRepo
import com.example.mpvlibrary.mpv.MPVPlayerView
import com.example.mpvlibrary.mpv.MpvPath
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AspectRatioMode(val title: String, val shortTitle: String) {
    BEST_FIT("기본 맞춤 (Best Fit)", "맞춤"),
    FIT_SCREEN("화면 채우기 (Fit Screen)", "채우기"),
    FILL("비율 무시 늘리기 (Fill)", "전체"),
    SIXTEEN_NINE("16:9 고정", "16:9"),
    FOUR_THREE("4:3 고정", "4:3"),
    ORIGINAL("원본 크기 (1:1)", "1:1");
}

enum class ScreenRotationMode(val label: String) {
    SENSOR("자동 회전 (센서)"),
    LANDSCAPE("가로 모드 고정"),
    PORTRAIT("세로 모드 고정");
}

enum class HudMode {
    NONE, BRIGHTNESS, VOLUME, SEEK, DOUBLE_TAP, FAST_PLAY, ZOOM, ASPECT, PLAY_PAUSE
}

data class TrackItem(
    val id: Int,
    val type: String, // "audio" or "sub"
    val title: String,
    val lang: String,
    val codec: String,
    val isSelected: Boolean,
) {
    val displayLabel: String
        get() {
            val sb = StringBuilder()
            if (title.isNotEmpty()) sb.append(title)
            else if (lang.isNotEmpty()) sb.append(lang.uppercase())
            else sb.append(if (type == "audio") "오디오 #$id" else "자막 #$id")
            if (codec.isNotEmpty()) sb.append(" [$codec]")
            return sb.toString()
        }
}

class PlayerActivity : ComponentActivity(), MPVLib.EventObserver, MPVLib.LogObserver {

    companion object {
        private const val TAG = "mpv"
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"

        fun start(context: Context, uris: List<String>, index: Int) {
            val i = Intent(context, PlayerActivity::class.java)
            i.putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            i.putExtra(EXTRA_INDEX, index)
            context.startActivity(i)
        }
    }

    private lateinit var settings: SettingsRepo
    private lateinit var audioManager: AudioManager
    private var maxVolume = 15

    private var playerView: MPVPlayerView? = null
    private var playable: MpvPath.Playable? = null
    private var uris: ArrayList<String> = arrayListOf()
    private var index = 0
    private var currentUri: String? = null
    private var sourceNote: String = ""
    private var lastPosition = 0.0
    private var lastDuration = 0.0
    private var pollJob: Job? = null
    private var initialized = false

    // Playback state
    private var position by mutableStateOf(0.0)
    private var duration by mutableStateOf(0.0)
    private var isPaused by mutableStateOf(false)
    private var speed by mutableStateOf(1.0)
    private var preFastPlaySpeed = 1.0
    private var speedPresets by mutableStateOf<List<Double>>(SettingsRepo.DEFAULT_SPEED_PRESETS)
    private var videoTitle by mutableStateOf("")
    private var autoAdvance by mutableStateOf(false)
    // P0: configurable gestures & repeat
    private var tapSeekSec by mutableStateOf(10.0)
    private var fastSpeedSetting by mutableStateOf(2.0)
    private var repeatOne by mutableStateOf(false)
    private var lastBrightness = -1f
    private var autoSubDoneIndex = -1
    private var subPlayables = mutableListOf<MpvPath.Playable>()
    private var noisyReceiver: android.content.BroadcastReceiver? = null

    // UI & Gesture controls
    private var controlsVisible by mutableStateOf(true)
    private var isLocked by mutableStateOf(false)
    private var isScrubbing by mutableStateOf(false)
    private var scrubPosition by mutableStateOf(0.0)
    private var currentAspectMode by mutableStateOf(AspectRatioMode.BEST_FIT)
    private var rotationMode by mutableStateOf(ScreenRotationMode.SENSOR)
    private var controlsTimerJob: Job? = null

    // Dialog states
    private var showSpeedDialog by mutableStateOf(false)
    private var showSubDialog by mutableStateOf(false)
    private var showAudioDialog by mutableStateOf(false)

    // Subtitle & Audio tracks
    private var subTracks by mutableStateOf<List<TrackItem>>(emptyList())
    private var audioTracks by mutableStateOf<List<TrackItem>>(emptyList())
    private var subDelaySec by mutableDoubleStateOf(0.0)
    private var audioDelaySec by mutableDoubleStateOf(0.0)

    // Gesture HUD feedback
    private var hudMode by mutableStateOf(HudMode.NONE)
    private var hudText by mutableStateOf("")
    private var hudValue by mutableFloatStateOf(0f)
    private var hudJob: Job? = null

    // Error
    private var errorBanner by mutableStateOf<String?>(null)

    /** Every native touchpoint funnels here: never throws, always logged. */
    private inline fun mpv(what: String, f: () -> Unit) {
        try {
            f()
        } catch (e: Exception) {
            AppLog.e(TAG, "$what failed: $e")
            errorBanner = "$what 실패: ${e.message}"
        }
    }

    private fun fail(msg: String) {
        AppLog.e(TAG, msg)
        errorBanner = msg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.install(this)
        AppLog.i(TAG, "PlayerActivity created (items=${intent.getStringArrayListExtra(EXTRA_URIS)?.size ?: 0})")
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        // P0: pause when headphones disconnect
        noisyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !isPaused && initialized && !isFinishing) {
                    AppLog.i(TAG, "headset disconnected — auto pause")
                    togglePlayPause()
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, noisyReceiver,
                android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { AppLog.w(TAG, "noisy receiver register failed: $it") }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        settings = SettingsRepo(this)
        uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        index = intent.getIntExtra(EXTRA_INDEX, 0)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // External subtitle file picker launcher
                val subPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {}
                        val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@PlayerActivity, uri)?.name ?: "sub.srt"
                        val cached = MpvPath.cacheCopy(this@PlayerActivity, uri, name)
                        if (cached != null) {
                            mpv("외부 자막 추가") {
                                MPVLib.command(arrayOf("sub-add", cached.absolutePath, "select"))
                                refreshTracks()
                                showHud(HudMode.ASPECT, "외부 자막 추가됨")
                            }
                        } else {
                            val subPlayable = MpvPath.open(this@PlayerActivity, uri)
                            subPlayables += subPlayable
                            mpv("외부 자막 추가") {
                                MPVLib.command(arrayOf("sub-add", subPlayable.path, "select"))
                                refreshTracks()
                                showHud(HudMode.ASPECT, "외부 자막 추가됨")
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MPVPlayerView(ctx, null).also { v ->
                                playerView = v
                                lifecycleScope.launch { setupPlayer(v) }
                            }
                        },
                    )

                    // VLC-style gesture layer
                    VlcGestureLayer()

                    // Center Gesture HUD feedback (Brightness, Volume, Seek, Zoom, FastPlay, etc.)
                    GestureHudOverlay()

                    // Error banner
                    val err = errorBanner
                    if (err != null) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(err, color = Color.White, modifier = Modifier.weight(1f))
                                IconButton(onClick = { errorBanner = null }) {
                                    Icon(Icons.Default.Close, "닫기", tint = Color.White)
                                }
                            }
                        }
                    }

                    // Floating unlock button when screen is locked
                    if (isLocked) {
                        FloatingUnlockButton()
                    } else {
                        // Full Player Controls overlay
                        AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ControlsOverlay()
                        }
                    }

                    // Subtitle Dialog
                    if (showSubDialog) {
                        SubtitleDialog(
                            tracks = subTracks,
                            delaySec = subDelaySec,
                            onSelectTrack = { id ->
                                mpv("자막 트랙 $id") {
                                    if (id <= 0) MPVLib.setPropertyString("sid", "no")
                                    else MPVLib.setPropertyString("sid", id.toString())
                                    refreshTracks()
                                }
                            },
                            onAdjustDelay = { deltaMs ->
                                val newSec = subDelaySec + (deltaMs / 1000.0)
                                subDelaySec = newSec
                                mpv("sub-delay $newSec") {
                                    MPVLib.setPropertyDouble("sub-delay", newSec)
                                }
                            },
                            onResetDelay = {
                                subDelaySec = 0.0
                                mpv("sub-delay 0.0") { MPVLib.setPropertyDouble("sub-delay", 0.0) }
                            },
                            onLoadExternalSub = { subPicker.launch(arrayOf("*/*")) },
                            onDismiss = { showSubDialog = false },
                        )
                    }

                    // Audio Track Dialog
                    if (showAudioDialog) {
                        AudioTrackDialog(
                            tracks = audioTracks,
                            delaySec = audioDelaySec,
                            onSelectTrack = { id ->
                                mpv("오디오 트랙 $id") {
                                    MPVLib.setPropertyString("aid", id.toString())
                                    refreshTracks()
                                }
                            },
                            onAdjustDelay = { deltaMs ->
                                val newSec = audioDelaySec + (deltaMs / 1000.0)
                                audioDelaySec = newSec
                                mpv("audio-delay $newSec") {
                                    MPVLib.setPropertyDouble("audio-delay", newSec)
                                }
                            },
                            onResetDelay = {
                                audioDelaySec = 0.0
                                mpv("audio-delay 0.0") { MPVLib.setPropertyDouble("audio-delay", 0.0) }
                            },
                            onDismiss = { showAudioDialog = false },
                        )
                    }

                    // Unified Speed Dialog
                    if (showSpeedDialog) {
                        SpeedDialog(
                            currentSpeed = speed,
                            presets = speedPresets,
                            onSelectSpeed = { s ->
                                applySpeed(s)
                                showSpeedDialog = false
                            },
                            onDismiss = { showSpeedDialog = false },
                        )
                    }
                }
            }
        }
    }

    private suspend fun setupPlayer(view: MPVPlayerView) {
        if (initialized) return
        initialized = true
        try {
            val configDir = filesDir.resolve("mpv-config").apply { mkdirs() }.absolutePath
            val cacheDir = cacheDir.resolve("mpv-cache").apply { mkdirs() }.absolutePath
            withContext(Dispatchers.Main) { view.initialize(configDir, cacheDir) }

            // Apply natural language video vertical alignment
            val alignY = settings.videoAlignY.first()
            mpv("video-align-y=$alignY") {
                val r = MPVLib.setOptionString("video-align-y", alignY)
                AppLog.i(TAG, "video-align-y applied: $alignY (result=$r)")
            }

            // User raw MPV options (excluding speed & video-align-y which are controlled via dedicated UI)
            val raw = settings.mpvOptionsRaw.first()
            for ((k, v) in SettingsRepo.parseOptions(raw)) {
                if (k == "speed" || k == "video-align-y") continue
                mpv("mpv 옵션 $k=$v") {
                    val r = MPVLib.setOptionString(k, v)
                    if (r < 0) AppLog.w(TAG, "mpv option rejected: $k=$v")
                    else AppLog.i(TAG, "mpv option applied: $k=$v")
                }
            }
            autoAdvance = settings.autoAdvance.first()
            speedPresets = settings.speedPresets.first()
            speed = settings.defaultSpeed.first()
            preFastPlaySpeed = speed
            mpv("speed=$speed") { MPVLib.setPropertyDouble("speed", speed) }
            // P0: configurable gestures & saved brightness
            tapSeekSec = settings.tapSeekSec.first().coerceIn(1.0, 60.0)
            fastSpeedSetting = settings.fastSpeed.first().coerceIn(1.25, 4.0)
            if (settings.rememberBrightness.first()) {
                val saved = settings.savedBrightness.first()
                if (saved in 0.01..1.0) {
                    lastBrightness = saved.toFloat()
                    runCatching {
                        window.attributes = window.attributes.apply { screenBrightness = lastBrightness }
                    }
                    AppLog.i(TAG, "restored brightness ${(saved * 100).roundToInt()}%")
                }
            }

            MPVLib.addObserver(this@PlayerActivity)
            MPVLib.addLogObserver(this@PlayerActivity)
            playCurrent(view)
            startPolling()
            resetControlsTimer()
        } catch (e: Exception) {
            fail("플레이어 초기화 실패: ${e.message}")
        }
    }

    private suspend fun playCurrent(view: MPVPlayerView) {
        val src = loadSource(index) ?: return
        withContext(Dispatchers.Main) { view.playFile(src.path) }
    }

    private suspend fun loadSource(i: Int): MpvPath.Playable? {
        val uriStr = uris.getOrNull(i)
        if (uriStr.isNullOrEmpty()) {
            fail("재생할 영상이 없음 (index=$i)")
            return null
        }
        currentUri = uriStr
        autoSubDoneIndex = -1
        subPlayables.forEach { runCatching { it.close() } }
        subPlayables.clear()
        val entity = withContext(Dispatchers.IO) { AppDb.get(this@PlayerActivity).videos().byUri(uriStr) }
        videoTitle = entity?.name ?: Uri.parse(uriStr).lastPathSegment ?: "동영상"
        val threshold = settings.watchedThreshold.first()
        val resume = entity?.positionSec?.takeIf { it > 5 && !entity.isWatched(threshold) } ?: 0.0
        mpv("이어보기 start=$resume") {
            MPVLib.setOptionString("start", if (resume > 0) resume.toString() else "0")
        }
        position = resume
        lastPosition = resume
        AppLog.i(TAG, "loading item $i resume=${resume.toInt()}s title=$videoTitle")
        val next = withContext(Dispatchers.IO) {
            try {
                MpvPath.open(this@PlayerActivity, Uri.parse(uriStr))
            } catch (e: Exception) {
                AppLog.e(TAG, "source resolve crashed: $e")
                null
            }
        }
        if (next == null || next.path.isEmpty()) {
            fail("파일을 열 수 없음: $videoTitle")
            return null
        }
        playable?.close()
        playable = next
        sourceNote = next.note
        return next
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.Main) {
            var tick = 0
            while (true) {
                try {
                    delay(1000)
                    val pos = MPVLib.getPropertyDouble("time-pos") ?: continue
                    val dur = MPVLib.getPropertyDouble("duration") ?: 0.0
                    if (!isScrubbing) {
                        position = pos
                        duration = dur
                    }
                    lastPosition = pos
                    lastDuration = dur
                    val pausedProp = MPVLib.getPropertyBoolean("pause")
                    if (pausedProp != null) isPaused = pausedProp

                    if (++tick % 3 == 0) {
                        refreshTracks()
                    }
                    if (tick % 5 == 0) persistProgress(pos, dur)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(TAG, "poll failed: ${e.message}")
                }
            }
        }
    }

    private fun refreshTracks() {
        runCatching {
            subTracks = loadTracks("sub")
            audioTracks = loadTracks("audio")
            subDelaySec = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
            audioDelaySec = MPVLib.getPropertyDouble("audio-delay") ?: 0.0
        }
    }

    private fun loadTracks(type: String): List<TrackItem> {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val list = mutableListOf<TrackItem>()
        for (i in 0 until count) {
            val t = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (t != type) continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: (i + 1)
            val title = MPVLib.getPropertyString("track-list/$i/title") ?: ""
            val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
            val codec = MPVLib.getPropertyString("track-list/$i/codec") ?: ""
            val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
            list.add(TrackItem(id, t, title, lang, codec, selected))
        }
        return list
    }

    private fun persistProgress(pos: Double, dur: Double) {
        val uri = currentUri ?: return
        if (dur <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppDb.get(this@PlayerActivity).videos()
                    .saveProgress(uri, pos, dur, System.currentTimeMillis())
            } catch (e: Exception) {
                AppLog.e(TAG, "saveProgress failed: $e")
            }
        }
    }

    private fun togglePlayPause() {
        val next = !isPaused
        isPaused = next
        mpv(if (next) "일시정지" else "재생") {
            MPVLib.setPropertyBoolean("pause", next)
        }
        showHud(HudMode.PLAY_PAUSE, if (next) "⏸ 일시정지" else "▶ 재생")
        resetControlsTimer()
    }

    private fun advance() {
        if (index >= uris.size - 1) return
        persistProgress(lastPosition, lastDuration)
        index += 1
        lifecycleScope.launch {
            val v = playerView ?: return@launch
            val src = loadSource(index) ?: return@launch
            withContext(Dispatchers.Main) { v.playFile(src.path) }
        }
        resetControlsTimer()
    }

    private fun previous() {
        if (position > 3.0) {
            mpv("처음으로 이동") {
                MPVLib.command(arrayOf("seek", "0", "absolute"))
                position = 0.0
                lastPosition = 0.0
            }
            showHud(HudMode.SEEK, "처음부터 재생 (0:00)")
        } else if (index > 0) {
            persistProgress(lastPosition, lastDuration)
            index -= 1
            lifecycleScope.launch {
                val v = playerView ?: return@launch
                val src = loadSource(index) ?: return@launch
                withContext(Dispatchers.Main) { v.playFile(src.path) }
            }
        }
        resetControlsTimer()
    }

    private fun seekRelative(deltaSec: Double) {
        val maxTarget = if (duration > 0) (duration - 0.5).coerceAtLeast(0.0) else 0.0
        val target = if (duration > 0) (position + deltaSec).coerceIn(0.0, maxTarget) else (position + deltaSec).coerceAtLeast(0.0)
        mpv("seek absolute $target") {
            MPVLib.command(arrayOf("seek", target.toString(), "absolute"))
            position = target
            lastPosition = target
        }
        showHud(HudMode.DOUBLE_TAP, if (deltaSec >= 0) "⏩ +${deltaSec.toInt()}초" else "⏪ ${deltaSec.toInt()}초")
        resetControlsTimer()
    }

    private fun cycleAspectRatio() {
        val modes = AspectRatioMode.entries
        val nextIndex = (modes.indexOf(currentAspectMode) + 1) % modes.size
        val next = modes[nextIndex]
        currentAspectMode = next
        when (next) {
            AspectRatioMode.BEST_FIT -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-unscaled", "no")
                MPVLib.setPropertyString("keepaspect", "yes")
            }
            AspectRatioMode.FIT_SCREEN -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 1.0)
                MPVLib.setPropertyString("video-unscaled", "no")
                MPVLib.setPropertyString("keepaspect", "yes")
            }
            AspectRatioMode.FILL -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-unscaled", "no")
                MPVLib.setPropertyString("keepaspect", "no")
            }
            AspectRatioMode.SIXTEEN_NINE -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-unscaled", "no")
                MPVLib.setPropertyString("video-aspect-override", "16:9")
            }
            AspectRatioMode.FOUR_THREE -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-unscaled", "no")
                MPVLib.setPropertyString("video-aspect-override", "4:3")
            }
            AspectRatioMode.ORIGINAL -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-unscaled", "yes")
                MPVLib.setPropertyString("keepaspect", "yes")
            }
        }
        showHud(HudMode.ASPECT, next.title)
        resetControlsTimer()
    }

    private fun cycleScreenRotation() {
        val next = when (rotationMode) {
            ScreenRotationMode.SENSOR -> ScreenRotationMode.LANDSCAPE
            ScreenRotationMode.LANDSCAPE -> ScreenRotationMode.PORTRAIT
            ScreenRotationMode.PORTRAIT -> ScreenRotationMode.SENSOR
        }
        rotationMode = next
        requestedOrientation = when (next) {
            ScreenRotationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            ScreenRotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenRotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        showHud(HudMode.ASPECT, "화면 회전: ${next.label}")
        resetControlsTimer()
    }

    private fun showHud(mode: HudMode, text: String, value: Float = 0f, autoDismiss: Boolean = true) {
        hudJob?.cancel()
        hudMode = mode
        hudText = text
        hudValue = value
        if (autoDismiss) {
            hudJob = lifecycleScope.launch {
                delay(1200)
                hudMode = HudMode.NONE
            }
        }
    }

    private fun resetControlsTimer() {
        controlsTimerJob?.cancel()
        if (controlsVisible && !isLocked && !isScrubbing) {
            controlsTimerJob = lifecycleScope.launch {
                delay(4500)
                controlsVisible = false
            }
        }
    }

    private fun applySpeed(s: Double) {
        speed = s
        preFastPlaySpeed = s
        mpv("speed=$s") { MPVLib.setPropertyDouble("speed", s) }
        AppLog.i(TAG, "speed set to $s")
        lifecycleScope.launch { settings.setDefaultSpeed(s) }
        showHud(HudMode.ASPECT, "재생 속도: ${s}x")
        resetControlsTimer()
    }

    // P0: repeat-one toggle via mpv loop-file
    private fun toggleRepeat() {
        repeatOne = !repeatOne
        mpv("반복 ${if (repeatOne) "켜짐" else "꺼짐"}") {
            MPVLib.setPropertyString("loop-file", if (repeatOne) "inf" else "no")
        }
        AppLog.i(TAG, "repeat-one=${repeatOne}")
        showHud(HudMode.ASPECT, if (repeatOne) "🔂 한곡 반복 켜짐" else "한곡 반복 꺼짐")
        resetControlsTimer()
    }

    // P0: auto-load same-basename external subtitles from the video folder
    private fun autoLoadSubtitles() {
        val uriStr = currentUri ?: return
        if (autoSubDoneIndex == index) return
        autoSubDoneIndex = index
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!settings.autoSubtitle.first()) return@launch
                val entity = AppDb.get(this@PlayerActivity).videos().byUri(uriStr) ?: return@launch
                val folder = AppDb.get(this@PlayerActivity).folders().byId(entity.folderId) ?: return@launch
                var dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    this@PlayerActivity, Uri.parse(folder.treeUri),
                ) ?: return@launch
                if (entity.dirPath.isNotEmpty()) {
                    for (seg in entity.dirPath.split('/')) {
                        dir = dir.findFile(seg) ?: return@launch
                    }
                }
                val matches = MpvPath.matchSubtitles(entity.name, dir.listFiles().mapNotNull { it.name })
                if (matches.isEmpty()) return@launch
                AppLog.i(TAG, "auto-sub found ${matches.size}: ${matches.joinToString()}")
                withContext(Dispatchers.Main) {
                    matches.forEachIndexed { i, name ->
                        val doc = dir.findFile(name) ?: return@forEachIndexed
                        // Real file path: mpv subtitle demuxers often reject fd://.
                        val cached = MpvPath.cacheCopy(this@PlayerActivity, doc.uri, name)
                            ?: return@forEachIndexed
                        mpv("자막 자동추가 $name") {
                            MPVLib.command(arrayOf("sub-add", cached.absolutePath, if (i == 0) "select" else "auto"))
                        }
                    }
                    refreshTracks()
                    showHud(HudMode.ASPECT, "외부 자막 ${matches.size}개 자동 로드")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "auto-sub failed: ${e.message}")
            }
        }
    }
    // ---------------------------------------------------------------- VLC Gesture Layer

    @Composable
    private fun VlcGestureLayer() {
        var startBrightness by remember { mutableFloatStateOf(0.5f) }
        var startVolume by remember { mutableIntStateOf(0) }
        var startSeekPosition by remember { mutableDoubleStateOf(0.0) }
        var currentSeekTarget by remember { mutableDoubleStateOf(0.0) }

        var lastTapTime by remember { mutableLongStateOf(0L) }
        var lastTapPos by remember { mutableStateOf(Offset.Zero) }
        var singleTapJob by remember { mutableStateOf<Job?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked, tapSeekSec, fastSpeedSetting) {
                    if (isLocked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val downTime = System.currentTimeMillis()
                        val downPos = down.position
                        val viewWidth = size.width.toFloat()
                        val viewHeight = size.height.toFloat()

                        startBrightness = window.attributes.screenBrightness.takeIf { it in 0.01f..1.0f } ?: 0.5f
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startSeekPosition = position
                        currentSeekTarget = position

                        var gestureDetermined = false
                        var isPinch = false
                        var isSeek = false
                        var isBrightness = false
                        var isVolume = false
                        var isFastPlay = false
                        var initialPinchDist = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // Multi-touch: Pinch zoom
                            if (pressed.size >= 2) {
                                if (!isPinch) {
                                    isPinch = true
                                    gestureDetermined = true
                                    val p1 = pressed[0].position
                                    val p2 = pressed[1].position
                                    initialPinchDist = (p1 - p2).getDistance().coerceAtLeast(10f)
                                } else {
                                    val p1 = pressed[0].position
                                    val p2 = pressed[1].position
                                    val dist = (p1 - p2).getDistance()
                                    val scale = (dist / initialPinchDist).coerceIn(0.5f, 3.0f)
                                    val zoomLevel = kotlin.math.log2(scale.toDouble())
                                    MPVLib.setPropertyDouble("video-zoom", zoomLevel)
                                    showHud(HudMode.ZOOM, "화면 배율: ${(scale * 100).roundToInt()}%", scale / 3.0f, autoDismiss = false)
                                }
                                pressed.forEach { it.consume() }
                                continue
                            }

                            // Single pointer gesture detection
                            val p = pressed[0]
                            val dx = p.position.x - downPos.x
                            val dy = p.position.y - downPos.y
                            val dist = kotlin.math.hypot(dx, dy)

                            if (!gestureDetermined) {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (dist > 18f) {
                                    gestureDetermined = true
                                    if (abs(dx) > abs(dy) * 1.2f) {
                                        isSeek = true
                                    } else if (abs(dy) > abs(dx) * 1.2f) {
                                        if (downPos.x < viewWidth / 2f) {
                                            isBrightness = true
                                        } else {
                                            isVolume = true
                                        }
                                    }
                                } else if (elapsed > 500) {
                                    // VLC Fast Play on Long Press
                                    gestureDetermined = true
                                    isFastPlay = true
                                    preFastPlaySpeed = speed
                                    val fs = fastSpeedSetting
                                    MPVLib.setPropertyDouble("speed", fs)
                                    showHud(HudMode.FAST_PLAY, "⚡ ${fs}x 쾌속 재생 중", autoDismiss = false)
                                }
                            }

                            if (isBrightness) {
                                val delta = -dy / (viewHeight * 0.75f)
                                val newB = (startBrightness + delta).coerceIn(0.01f, 1.0f)
                                lastBrightness = newB
                                window.attributes = window.attributes.apply { screenBrightness = newB }
                                showHud(HudMode.BRIGHTNESS, "밝기: ${(newB * 100).roundToInt()}%", newB, autoDismiss = false)
                                p.consume()
                            } else if (isVolume) {
                                val delta = -dy / (viewHeight * 0.75f)
                                val newVol = (startVolume + (delta * maxVolume)).roundToInt().coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                val pct = (newVol * 100) / maxVolume
                                showHud(HudMode.VOLUME, "음량: $pct%", newVol / maxVolume.toFloat(), autoDismiss = false)
                                p.consume()
                            } else if (isSeek) {
                                val deltaSec = (dx / viewWidth) * 90.0
                                val target = (startSeekPosition + deltaSec).coerceIn(0.0, duration.coerceAtLeast(1.0))
                                currentSeekTarget = target
                                val diff = target - startSeekPosition
                                val sign = if (diff >= 0) "+" else ""
                                showHud(
                                    HudMode.SEEK,
                                    "${fmt(startSeekPosition)} → ${fmt(target)} ($sign${fmt(diff)})",
                                    (target / duration.coerceAtLeast(1.0)).toFloat(),
                                    autoDismiss = false,
                                )
                                p.consume()
                            } else if (isFastPlay) {
                                p.consume()
                            }
                        }

                        // On touch release
                        if (isSeek) {
                            mpv("seek absolute $currentSeekTarget") {
                                MPVLib.command(arrayOf("seek", currentSeekTarget.toString(), "absolute"))
                                position = currentSeekTarget
                                lastPosition = currentSeekTarget
                            }
                            showHud(HudMode.SEEK, "이동: ${fmt(currentSeekTarget)}")
                        } else if (isFastPlay) {
                            MPVLib.setPropertyDouble("speed", preFastPlaySpeed)
                            showHud(HudMode.NONE, "")
                        } else if (isPinch || isBrightness || isVolume) {
                            if (isBrightness && lastBrightness > 0) {
                                val b = lastBrightness.toDouble()
                                lifecycleScope.launch { settings.setSavedBrightness(b) }
                            }
                            hudJob = lifecycleScope.launch {
                                delay(1200)
                                hudMode = HudMode.NONE
                            }
                        } else if (!gestureDetermined) {
                            // Tap / Double-tap detection
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 320 && (downPos - lastTapPos).getDistance() < 120f) {
                                // Double tap!
                                singleTapJob?.cancel()
                                lastTapTime = 0L
                                when {
                                    downPos.x < viewWidth * 0.33f -> seekRelative(-tapSeekSec)
                                    downPos.x > viewWidth * 0.67f -> seekRelative(tapSeekSec)
                                    else -> togglePlayPause()
                                }
                            } else {
                                lastTapTime = now
                                lastTapPos = downPos
                                singleTapJob = lifecycleScope.launch {
                                    delay(300)
                                    controlsVisible = !controlsVisible
                                    resetControlsTimer()
                                }
                            }
                        }
                    }
                }
        )
    }

    // ---------------------------------------------------------------- Centered Gesture HUD

    @Composable
    private fun BoxScope.GestureHudOverlay() {
        if (hudMode == HudMode.NONE) return

        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (hudMode) {
                    HudMode.BRIGHTNESS -> {
                        Icon(Icons.Default.WbSunny, "밝기", tint = Color(0xFFFFB300), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { hudValue },
                            modifier = Modifier.width(140.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFFFFB300),
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HudMode.VOLUME -> {
                        Icon(
                            if (hudValue <= 0.01f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                            "음량", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { hudValue },
                            modifier = Modifier.width(140.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HudMode.SEEK -> {
                        Icon(Icons.Default.FastForward, "탐색", tint = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    HudMode.DOUBLE_TAP -> {
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    HudMode.FAST_PLAY -> {
                        Icon(Icons.Default.Bolt, "배속", tint = Color(0xFFFFD54F), modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(hudText, color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HudMode.ZOOM -> {
                        Icon(Icons.Default.ZoomIn, "줌", tint = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HudMode.ASPECT -> {
                        Icon(Icons.Default.AspectRatio, "화면 비율", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HudMode.PLAY_PAUSE -> {
                        Text(hudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                    HudMode.NONE -> {}
                }
            }
        }
    }

    // ---------------------------------------------------------------- Controls Overlay

    @Composable
    private fun ControlsOverlay() {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.82f),
                        0.22f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.92f),
                    )
                )
                .safeDrawingPadding()
        ) {
            // Top Bar: Back, Title, Subtitles, Audio, Rotation, Speed, Lock
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기", tint = Color.White)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = videoTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uris.size > 1) {
                    Text(
                        "${index + 1}/${uris.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // Subtitle Selection Button
                IconButton(onClick = {
                    refreshTracks()
                    showSubDialog = true
                }) {
                    Icon(
                        Icons.Default.Subtitles, "자막 선택",
                        tint = if (subTracks.any { it.isSelected }) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }

                // Audio Track Selection Button
                IconButton(onClick = {
                    refreshTracks()
                    showAudioDialog = true
                }) {
                    Icon(
                        Icons.Default.Audiotrack, "오디오 트랙",
                        tint = if (audioTracks.size > 1) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }

                // Screen Rotation Toggle Button
                IconButton(onClick = { cycleScreenRotation() }) {
                    Icon(
                        Icons.Default.ScreenRotation, "화면 회전",
                        tint = if (rotationMode == ScreenRotationMode.SENSOR) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }

                // Unified Speed Button
                OutlinedButton(
                    onClick = { showSpeedDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.4f)))
                    ),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (speed % 1.0 == 0.0) "${speed.toInt()}x" else "${speed}x", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(4.dp))

                // Screen Lock Button
                IconButton(onClick = {
                    isLocked = true
                    controlsVisible = false
                    showHud(HudMode.NONE, "")
                }) {
                    Icon(Icons.Default.LockOpen, "화면 잠금", tint = Color.White)
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom Player Controls
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Interactive Seek Bar (Slider) — 2x thicker track (10dp) with prominent 20dp thumb
                val sliderColors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                )
                Slider(
                    value = (if (isScrubbing) scrubPosition else position).toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = {
                        isScrubbing = true
                        scrubPosition = it.toDouble()
                        resetControlsTimer()
                    },
                    onValueChangeFinished = {
                        isScrubbing = false
                        val maxTarget = if (duration > 0) (duration - 0.5).coerceAtLeast(0.0) else 0.0
                        val target = scrubPosition.coerceIn(0.0, maxTarget)
                        mpv("seek absolute $target") {
                            MPVLib.command(arrayOf("seek", target.toString(), "absolute"))
                            position = target
                            lastPosition = target
                        }
                        resetControlsTimer()
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = sliderColors,
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(10.dp),
                            colors = sliderColors,
                        )
                    },
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = remember { MutableInteractionSource() },
                            modifier = Modifier.size(20.dp),
                            colors = sliderColors,
                        )
                    },
                )

                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                if (isLandscape) {
                    // Landscape layout: Single unified bottom line bringing Time and Aspect Ratio
                    // down aligned with the playback buttons, eliminating empty gaps on wide screens.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left: Time info
                        val curTime = fmt(if (isScrubbing) scrubPosition else position)
                        val durTime = fmt(duration)
                        val remainSec = (duration - (if (isScrubbing) scrubPosition else position)).coerceAtLeast(0.0)
                        val remainTime = "-${fmt(remainSec)}"
                        Text(
                            "$curTime / $durTime ($remainTime)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )

                        // Center: Media controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            IconButton(onClick = { previous() }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Default.SkipPrevious, "이전 영상", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { seekRelative(-tapSeekSec) }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Replay10, "${tapSeekSec.toInt()}초 뒤로", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            FilledIconButton(
                                onClick = { togglePlayPause() },
                                modifier = Modifier.size(52.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    if (isPaused) "재생" else "일시정지",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = { seekRelative(tapSeekSec) }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Forward10, "${tapSeekSec.toInt()}초 앞으로", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { advance() }, enabled = index < uris.size - 1, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    Icons.Default.SkipNext, "다음 영상",
                                    tint = if (index < uris.size - 1) Color.White else Color.Gray,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }

                        // Right: Repeat & Aspect Ratio
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { toggleRepeat() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.RepeatOne, "한곡 반복",
                                    tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            OutlinedButton(
                                onClick = { cycleAspectRatio() },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.4f)))
                                ),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(currentAspectMode.shortTitle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    // Portrait layout (narrow width): Time & Aspect on upper row, Media buttons on lower row
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val curTime = fmt(if (isScrubbing) scrubPosition else position)
                        val durTime = fmt(duration)
                        val remainSec = (duration - (if (isScrubbing) scrubPosition else position)).coerceAtLeast(0.0)
                        val remainTime = "-${fmt(remainSec)}"
                        Text(
                            "$curTime / $durTime ($remainTime)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))

                        IconButton(
                            onClick = { toggleRepeat() },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Default.RepeatOne, "한곡 반복",
                                tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        OutlinedButton(
                            onClick = { cycleAspectRatio() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.4f)))
                            ),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(currentAspectMode.shortTitle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Centered Professional Media Playback Controls
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { previous() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipPrevious, "이전 영상", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { seekRelative(-tapSeekSec) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Replay10, "${tapSeekSec.toInt()}초 뒤로", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(
                            onClick = { togglePlayPause() },
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                if (isPaused) "재생" else "일시정지",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { seekRelative(tapSeekSec) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Forward10, "${tapSeekSec.toInt()}초 앞으로", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { advance() }, enabled = index < uris.size - 1, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.SkipNext, "다음 영상",
                                tint = if (index < uris.size - 1) Color.White else Color.Gray,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Subtitle Dialog

    @Composable
    private fun SubtitleDialog(
        tracks: List<TrackItem>,
        delaySec: Double,
        onSelectTrack: (Int) -> Unit,
        onAdjustDelay: (Double) -> Unit,
        onResetDelay: () -> Unit,
        onLoadExternalSub: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val currentSelectedId = tracks.find { it.isSelected }?.id ?: -1
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Subtitles, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("자막 선택")
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("자막 트랙 목록", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))

                    Column(Modifier.selectableGroup()) {
                        // Option: Disable subtitles
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = currentSelectedId == -1,
                                    onClick = { onSelectTrack(-1) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentSelectedId == -1, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text("자막 끄기", fontWeight = if (currentSelectedId == -1) FontWeight.Bold else FontWeight.Normal)
                        }

                        // Internal/embedded subtitle tracks
                        tracks.forEach { trk ->
                            val selected = trk.isSelected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .selectable(
                                        selected = selected,
                                        onClick = { onSelectTrack(trk.id) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    trk.displayLabel,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onLoadExternalSub,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("외부 자막 파일 불러오기 (.srt, .vtt, .ass)")
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Subtitle Delay / Sync adjustment
                    Text(
                        "자막 싱크: ${(delaySec * 1000).roundToInt()}ms",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = { onAdjustDelay(-100.0) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("-0.1초")
                        }
                        FilledTonalButton(
                            onClick = onResetDelay,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("초기화")
                        }
                        FilledTonalButton(
                            onClick = { onAdjustDelay(100.0) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("+0.1초")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("닫기") }
            },
        )
    }

    // ---------------------------------------------------------------- Audio Track Dialog

    @Composable
    private fun AudioTrackDialog(
        tracks: List<TrackItem>,
        delaySec: Double,
        onSelectTrack: (Int) -> Unit,
        onAdjustDelay: (Double) -> Unit,
        onResetDelay: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val currentSelectedId = tracks.find { it.isSelected }?.id ?: 1
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("오디오 트랙 선택")
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("오디오 스트림 목록", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))

                    if (tracks.isEmpty()) {
                        Text("사용 가능한 오디오 트랙이 1개입니다 (기본 트랙)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        Column(Modifier.selectableGroup()) {
                            tracks.forEach { trk ->
                                val selected = trk.isSelected
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .selectable(
                                            selected = selected,
                                            onClick = { onSelectTrack(trk.id) },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        trk.displayLabel,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Audio Delay / Sync adjustment
                    Text(
                        "오디오 싱크: ${(delaySec * 1000).roundToInt()}ms",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = { onAdjustDelay(-100.0) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("-0.1초")
                        }
                        FilledTonalButton(
                            onClick = onResetDelay,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("초기화")
                        }
                        FilledTonalButton(
                            onClick = { onAdjustDelay(100.0) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("+0.1초")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("닫기") }
            },
        )
    }

    // ---------------------------------------------------------------- Unified Speed Dialog

    @Composable
    private fun SpeedDialog(
        currentSpeed: Double,
        presets: List<Double>,
        onSelectSpeed: (Double) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var tempSpeed by remember { mutableDoubleStateOf(currentSpeed) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("재생 속도 (현재: ${(tempSpeed * 100).roundToInt() / 100.0}x)")
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("프리셋 바로가기", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presets.forEach { s ->
                            val isSel = abs(s - tempSpeed) < 0.01
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    tempSpeed = s
                                    onSelectSpeed(s)
                                },
                                label = {
                                    Text(if (s % 1.0 == 0.0) "${s.toInt()}x" else "${s}x")
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text("미세 속도 조절", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val s = ((tempSpeed - 0.05) * 100.0).roundToInt() / 100.0
                                tempSpeed = s.coerceIn(0.25, 3.0)
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Remove, "속도 감소")
                        }

                        Slider(
                            value = tempSpeed.toFloat(),
                            onValueChange = { tempSpeed = (it * 100.0).roundToInt() / 100.0 },
                            valueRange = 0.25f..3.0f,
                            modifier = Modifier.weight(1f),
                        )

                        IconButton(
                            onClick = {
                                val s = ((tempSpeed + 0.05) * 100.0).roundToInt() / 100.0
                                tempSpeed = s.coerceIn(0.25, 3.0)
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Add, "속도 증가")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            onClick = {
                                tempSpeed = 1.0
                                onSelectSpeed(1.0)
                            },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("1.0x (표준 속도)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSelectSpeed(tempSpeed) },
                ) {
                    Text("적용")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("취소") }
            },
        )
    }

    // ---------------------------------------------------------------- Floating Unlock Button

    @Composable
    private fun BoxScope.FloatingUnlockButton() {
        FilledIconButton(
            onClick = {
                isLocked = false
                controlsVisible = true
                resetControlsTimer()
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .safeDrawingPadding()
                .padding(20.dp)
                .size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        ) {
            Icon(Icons.Default.Lock, "화면 잠금 해제", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    // ---------------------------------------------------------------- External Media Buttons

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (isPaused) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (!isPaused) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    advance()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    previous()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekRelative(tapSeekSec)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekRelative(-tapSeekSec)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    // MPVLib events (called on mpv thread — never touch UI directly).
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}
    override fun event(eventId: Int) {
        try {
            AppLog.i(TAG, "mpv event id=$eventId")
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                persistProgress(lastPosition, lastDuration)
                if (autoAdvance) runOnUiThread { advance() }
            }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                AppLog.i(TAG, "file loaded (pos=${lastPosition.toInt()}s)")
                runOnUiThread { refreshTracks() }
                autoLoadSubtitles()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "event handler failed: $e")
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            AppLog.line("M", prefix, text.trim())
        }
    }

    override fun onPause() {
        super.onPause()
        persistProgress(lastPosition, lastDuration)
        if (initialized) mpv("pause") { MPVLib.setPropertyBoolean("pause", true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        controlsTimerJob?.cancel()
        hudJob?.cancel()
        persistProgress(lastPosition, lastDuration)
        runCatching { playable?.close() }
        playable = null
        subPlayables.forEach { runCatching { it.close() } }
        subPlayables.clear()
        runCatching { noisyReceiver?.let { unregisterReceiver(it) } }
        noisyReceiver = null
        if (initialized) {
            runCatching {
                MPVLib.removeObserver(this)
                MPVLib.removeLogObserver(this)
                playerView?.destroy()
            }.onFailure { AppLog.e(TAG, "teardown: $it") }
            initialized = false
        }
        AppLog.i(TAG, "PlayerActivity destroyed")
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
