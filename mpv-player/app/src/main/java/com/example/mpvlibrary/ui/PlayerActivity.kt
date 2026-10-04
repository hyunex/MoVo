package com.example.mpvlibrary.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.mpvlibrary.data.AppDb
import com.example.mpvlibrary.data.AppLog
import com.example.mpvlibrary.data.LibraryWork
import com.example.mpvlibrary.data.PlayerPlaylistStore
import com.example.mpvlibrary.data.SettingsRepo
import com.example.mpvlibrary.data.VideoAlign
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

// 자동 회전 ON: 4방향 전체 센서. OFF: 토글 시점의 현재 방향으로 고정.

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

enum class PlaybackEndAction { IGNORE, ERROR, REPEAT, ADVANCE, PAUSE }

fun playbackEndAction(
    reason: Int,
    error: Int,
    endingEntry: Long,
    activeEntry: Long?,
    repeat: Boolean,
    advance: Boolean,
): PlaybackEndAction {
    if (activeEntry != endingEntry) return PlaybackEndAction.IGNORE
    if (reason == MPVLib.MpvEndFile.MPV_END_FILE_REASON_ERROR || error < 0) return PlaybackEndAction.ERROR
    if (reason != MPVLib.MpvEndFile.MPV_END_FILE_REASON_EOF) return PlaybackEndAction.IGNORE
    return when {
        repeat -> PlaybackEndAction.REPEAT
        advance -> PlaybackEndAction.ADVANCE
        else -> PlaybackEndAction.PAUSE
    }
}

/** Wall-clock estimate only; playback positions and duration remain in media seconds. */
fun playbackRemainingSeconds(duration: Double, position: Double, effectiveSpeed: Double): Double {
    if (!duration.isFinite() || duration <= 0.0 || !position.isFinite() ||
        !effectiveSpeed.isFinite() || effectiveSpeed <= 0.0
    ) return 0.0
    val remaining = (duration - position).coerceAtLeast(0.0) / effectiveSpeed
    return if (remaining.isFinite()) remaining else 0.0
}

/** Recreation is a continuation, unlike opening a library item using its resume threshold. */
fun playbackResumePosition(restart: Boolean, sessionPosition: Double?, libraryPosition: Double, libraryWatched: Boolean): Double {
    if (restart) return 0.0
    if (sessionPosition != null && sessionPosition.isFinite() && sessionPosition >= 0) return sessionPosition
    return libraryPosition.takeIf { it.isFinite() && it > 5 && !libraryWatched } ?: 0.0
}

/** User intent and audio ownership are independent of the engine's pause property. */
class PlaybackIntent {
    var wantsPlay = true
        private set
    var foreground = false
        private set
    var hasFocus = false
        private set
    val canPlay: Boolean get() = wantsPlay && foreground && hasFocus
    fun play() { wantsPlay = true }
    fun pause() { wantsPlay = false }
    fun foreground(value: Boolean) { foreground = value }
    fun focus(gained: Boolean, permanentLoss: Boolean = false) {
        hasFocus = gained
        if (permanentLoss) wantsPlay = false
    }
}

/** A requested seek remains authoritative until a matching native sample arrives. */
class RequestedSeek(private val clock: () -> Long = System::nanoTime) {
    var target: Double? = null
        private set
    private var requestedAt = 0L
    fun request(position: Double) {
        target = position
        requestedAt = clock()
    }
    fun reset() { target = null }
    fun sample(position: Double): Double = target ?: position
    fun confirm(position: Double, effectiveSpeed: Double = 1.0): Double {
        val requested = target ?: return position
        val elapsed = ((clock() - requestedAt).coerceAtLeast(0) / 1_000_000_000.0)
        val drift = maxOf(2.0, elapsed * effectiveSpeed.coerceAtLeast(0.0) + 0.25)
        if (position.isFinite() && position >= requested - 0.05 && position <= requested + drift) {
            target = null
            return position
        }
        return requested
    }
}

class PlayerActivity : ComponentActivity(), MPVLib.EventObserver, MPVLib.LogObserver {

    companion object {
        private const val TAG = "mpv"
        private const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_INDEX = "index"

        fun start(context: Context, uris: List<String>, index: Int) {
            val application = context.applicationContext
            LibraryWork.scope.launch {
                val store = PlayerPlaylistStore(application.filesDir.resolve("player-playlists"))
                var token: String? = null
                try {
                    val saved = store.save(uris)
                    token = saved
                    val i = Intent(context, PlayerActivity::class.java)
                    i.putExtra(EXTRA_PLAYLIST, saved)
                    i.putExtra(EXTRA_INDEX, index)
                    withContext(Dispatchers.Main) {
                        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
                            store.remove(saved)
                        } else {
                            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }
                    }
                } catch (e: Exception) {
                    token?.let { saved ->
                        runCatching { store.remove(saved) }
                            .onFailure { AppLog.w(TAG, "playlist launch cleanup failed: ${it.message}") }
                    }
                    AppLog.e(TAG, "playlist launch failed: $e")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(application, "재생 목록을 열 수 없습니다", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private lateinit var settings: SettingsRepo
    private lateinit var audioManager: AudioManager
    private var maxVolume = 15

    private var playerView: MPVPlayerView? = null
    private var playable: MpvPath.Playable? = null
    private var uris: ArrayList<String> = arrayListOf()
    private var playlistToken: String? = null
    private var index by mutableIntStateOf(0)
    private var currentUri: String? = null
    private var sourceNote: String = ""
    private var lastPosition = 0.0
    private var lastDuration = 0.0
    private var pollJob: Job? = null
    private var initialized = false
    private var activeEntryId: Long? = null
    private var loadPending = true
    private var loadSubmitted = false
    private var rejectedEntryId: Long? = null
    @Volatile private var destroyed = false
    @Volatile private var loadGeneration = 0L
    private var loadedEntryUri: String? = null


    // Playback state
    private var position by mutableStateOf(0.0)
    private var duration by mutableStateOf(0.0)
    private var isPaused by mutableStateOf(false)
    // Effective engine speed; long-press overrides are restored without changing preferences.
    private var speed by mutableStateOf(1.0)
    private var preFastPlaySpeed = 1.0
    private var speedPresets by mutableStateOf<List<Double>>(SettingsRepo.DEFAULT_SPEED_PRESETS)
    private var speedStep by mutableDoubleStateOf(SettingsRepo.DEFAULT_SPEED_STEP)
    private var videoTitle by mutableStateOf("")
    private var autoAdvance by mutableStateOf(false)
    // P0: configurable gestures & repeat
    private var tapSeekSec by mutableStateOf(10.0)
    private var fastSpeedSetting by mutableStateOf(2.0)
    private var repeatOne by mutableStateOf(false)
    private var lastBrightness = -1f
    private var autoSubDoneIndex = -1
    private var subPlayables = mutableListOf<MpvPath.Playable>()
    private val externalSubtitleUris = arrayListOf<String>()
    private var noisyReceiver: android.content.BroadcastReceiver? = null

    // Bluetooth / Car AV MediaSession & AudioFocus
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val playbackIntent = PlaybackIntent()
    private val requestedSeek = RequestedSeek()
    private var restoredSession: Bundle? = null
    private var restoredPlaybackUi: Bundle? = null
    private var fastPlayHeld = false

    // UI & Gesture controls
    private var controlsVisible by mutableStateOf(true)
    private var isLocked by mutableStateOf(false)
    private var isScrubbing by mutableStateOf(false)
    private var scrubPosition by mutableStateOf(0.0)
    private var currentAspectMode by mutableStateOf(AspectRatioMode.BEST_FIT)
    private var currentVideoAlign by mutableStateOf(VideoAlign.TOP)
    private var autoRotate by mutableStateOf(true)
    private var controlsTimerJob: Job? = null
    private var gestureEpoch by mutableIntStateOf(0)

    // Dialog states
    private var showSpeedDialog by mutableStateOf(false)
    private var showSubDialog by mutableStateOf(false)
    private var showAudioDialog by mutableStateOf(false)
    private var showDisplayDialog by mutableStateOf(false)

    // Subtitle & Audio tracks
    private var subTracks by mutableStateOf<List<TrackItem>>(emptyList())
    private var audioTracks by mutableStateOf<List<TrackItem>>(emptyList())
    private var subDelaySec by mutableDoubleStateOf(0.0)
    private var audioDelaySec by mutableDoubleStateOf(0.0)
    private var subFontSize by mutableStateOf(SettingsRepo.DEFAULT_SUB_FONT_SIZE)
    private var subColorHex by mutableStateOf(SettingsRepo.DEFAULT_SUB_COLOR)

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        AppLog.install(this)
        AppLog.i(TAG, "PlayerActivity created")
        // P0: pause when headphones disconnect
        noisyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !isPaused && initialized && !isFinishing) {
                    AppLog.i(TAG, "headset disconnected — auto pause")
                    setUserPlayback(false)
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
        playlistToken = intent.getStringExtra(EXTRA_PLAYLIST)
        restoredSession = savedInstanceState
        index = savedInstanceState?.getInt("session.index") ?: intent.getIntExtra(EXTRA_INDEX, 0)
        savedInstanceState?.let {
            if (!it.getBoolean("session.play", true)) playbackIntent.pause()
            controlsVisible = it.getBoolean("session.controls", true)
            isLocked = it.getBoolean("session.locked", false)
            autoRotate = it.getBoolean("session.rotate", true)
            currentAspectMode = AspectRatioMode.entries.getOrNull(it.getInt("session.aspect")) ?: AspectRatioMode.BEST_FIT
            requestedOrientation = if (autoRotate) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else it.getInt("session.orientation", ActivityInfo.SCREEN_ORIENTATION_LOCKED)
            showSpeedDialog = it.getBoolean("session.speedDialog")
            showAudioDialog = it.getBoolean("session.audioDialog")
            showSubDialog = it.getBoolean("session.subDialog")
        }
        lifecycleScope.launch {
            try {
                uris = withContext(Dispatchers.IO) {
                    PlayerPlaylistStore(filesDir.resolve("player-playlists")).load(checkNotNull(playlistToken))
                }
                finishCreate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("재생 목록을 열 수 없습니다: ${e.message}")
                android.widget.Toast.makeText(this@PlayerActivity, "재생 목록을 열 수 없습니다", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun finishCreate() {
        index = index.coerceIn(0, maxOf(0, uris.size - 1))
        if (uris.isEmpty()) {
            fail("재생할 영상이 없음")
            finish()
            return
        }
        initMediaSession()
        if (playbackIntent.wantsPlay && playbackIntent.foreground && !playbackIntent.hasFocus) requestAudioFocus()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var showTitleDialog by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(controlsVisible, isLocked) { updateSystemBars() }
                // External subtitle file picker launcher
                val subPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {}
                        val generation = loadGeneration
                        lifecycleScope.launch {
                            addExternalSubtitle(uri, generation)
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
                            ControlsOverlay(onShowTitle = { showTitleDialog = true })
                        }
                    }

                    // Same un-inset bounds as the video view, independent of the control column.
                    // This clickable sibling sits above the gesture layer in hit testing.
                    if (controlsVisible && !isLocked) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalIconButton(
                                onClick = { previousFile() },
                                enabled = index > 0,
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Default.SkipPrevious, "이전 파일", modifier = Modifier.size(28.dp))
                            }
                            FilledIconButton(
                                onClick = { togglePlayPause() },
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    if (isPaused) "재생" else "일시정지",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { advance() },
                                enabled = index < uris.size - 1,
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Default.SkipNext, "다음 파일", modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    if (showTitleDialog) {
                        AlertDialog(
                            onDismissRequest = { showTitleDialog = false },
                            title = { Text("영상 제목") },
                            text = {
                                Text(
                                    videoTitle,
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showTitleDialog = false }) { Text("닫기") }
                            },
                        )
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
                            fontSize = subFontSize,
                            subColor = subColorHex,
                            onFontSizeChange = { v ->
                                subFontSize = v.coerceIn(20.0, 120.0)
                                applySubStyle()
                            },
                            onFontSizeFinal = { lifecycleScope.launch { settings.setSubFontSize(subFontSize) } },
                            onSubColor = { c ->
                                subColorHex = c
                                applySubStyle()
                                lifecycleScope.launch { settings.setSubColor(c) }
                            },
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
                            step = speedStep,
                            onSelectSpeed = { s ->
                                applySpeed(s)
                                showSpeedDialog = false
                            },
                            onDismiss = { showSpeedDialog = false },
                        )
                    }
                    if (showDisplayDialog) {
                        DisplayDialog(onDismiss = {
                            showDisplayDialog = false
                            resetControlsTimer()
                        })
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
            currentVideoAlign = VideoAlign.fromValue(alignY)
            mpv("video-align-y=$alignY") {
                val r = MPVLib.setOptionString("video-align-y", alignY)
                AppLog.i(TAG, "video-align-y applied: $alignY (result=$r)")
            }

            // User raw MPV options (excluding speed & video-align-y which are controlled via dedicated UI)
            val raw = settings.mpvOptionsRaw.first()
            val parsed = SettingsRepo.parseMpvOptions(raw)
            if (parsed.rejected.isNotEmpty()) {
                fail("일부 고급 MPV 옵션을 적용하지 않았습니다. 설정에서 차단된 줄을 삭제해 주세요.\n${parsed.rejectionMessage()}")
            }
            for ((k, v) in parsed.options) {
                if (k == "speed" || k == "video-align-y") continue
                mpv("mpv 옵션 $k") {
                    val r = MPVLib.setOptionString(k, v)
                    if (r < 0) AppLog.w(TAG, "mpv option rejected: $k")
                    else AppLog.i(TAG, "mpv option applied: $k")
                }
            }
            autoAdvance = settings.autoAdvance.first()
            speedPresets = settings.speedPresets.first()
            speedStep = settings.speedStep.first().let { step ->
                if (step.isFinite() && step in 0.01..1.0) {
                    (step * 100.0).roundToInt() / 100.0
                } else {
                    SettingsRepo.DEFAULT_SPEED_STEP
                }
            }
            speed = (restoredSession?.getDouble("session.speed") ?: settings.defaultSpeed.first()).coerceIn(0.1, 5.0)
            preFastPlaySpeed = speed
            mpv("speed=$speed") { MPVLib.setPropertyDouble("speed", speed) }
            mpv("restore aspect") { applyAspectRatio(currentAspectMode) }
            lifecycleScope.launch {
                settings.speedStep.collect { step ->
                    if (step.isFinite() && step in 0.01..1.0) {
                        speedStep = (step * 100.0).roundToInt() / 100.0
                    }
                }
            }
            lifecycleScope.launch {
                settings.speedPresets.collect { presets ->
                    if (presets.isNotEmpty()) {
                        speedPresets = presets
                    }
                }
            }
            subFontSize = settings.subFontSize.first().coerceIn(20.0, 120.0)
            subColorHex = settings.subColor.first().takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
                ?: SettingsRepo.DEFAULT_SUB_COLOR
            applySubStyle()
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
        val generation = ++loadGeneration
        loadPending = true
        loadSubmitted = false
        val src = loadSource(index, generation) ?: return
        if (generation != loadGeneration || destroyed) return
        loadSubmitted = true
        applyPlaybackGate()
        view.playFile(src.path)
        updateMediaSessionMetadata()
        updateMediaSessionState()
    }
    private suspend fun loadSource(i: Int, generation: Long, restart: Boolean = false): MpvPath.Playable? {
        val uriStr = uris.getOrNull(i)
        if (uriStr.isNullOrEmpty()) {
            fail("재생할 영상이 없음 (index=$i)")
            return null
        }
        // Resolve without reassigning the visible/current item until this request wins.
        autoSubDoneIndex = -1
        subPlayables.forEach { runCatching { it.close() } }
        subPlayables.clear()
        val entity = withContext(Dispatchers.IO) { AppDb.get(this@PlayerActivity).videos().byUri(uriStr) }
        if (generation != loadGeneration || destroyed) return null
        val title = entity?.name ?: Uri.parse(uriStr).lastPathSegment ?: "동영상"
        val threshold = settings.watchedThreshold.first()
        val session = restoredSession?.takeIf { it.getString("session.uri") == uriStr }
        val resume = playbackResumePosition(restart, session?.getDouble("session.position"),
            entity?.positionSec ?: 0.0, entity?.isWatched(threshold) ?: false)
        var resolved: MpvPath.Playable? = null
        val next = try {
            withContext(Dispatchers.IO) {
                try {
                    MpvPath.open(this@PlayerActivity, Uri.parse(uriStr)).also { resolved = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "source resolve crashed: $e")
                    null
                }
            }
        } catch (e: CancellationException) {
            resolved?.close()
            throw e
        }
        if (generation != loadGeneration || destroyed) {
            next?.close()
            return null
        }
        if (next == null || next.path.isEmpty()) {
            next?.close()
            fail("파일을 열 수 없음: $title")
            return null
        }
        playable?.close()
        playable = next
        sourceNote = next.note
        currentUri = uriStr
        videoTitle = title
        position = resume
        lastPosition = resume
        lastDuration = session?.getDouble("session.duration")?.takeIf { it.isFinite() && it > 0 }
            ?: entity?.durationSec?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        duration = lastDuration
        requestedSeek.reset()
        restoredPlaybackUi = session
        externalSubtitleUris.clear()
        restoredSession = null
        loadedEntryUri = uriStr
        mpv("이어보기 start=$resume") {
            MPVLib.setOptionString("start", if (resume > 0) resume.toString() else "0")
        }
        AppLog.i(TAG, "loading item $i resume=${resume.toInt()}s title=$videoTitle")
        return next
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.Main) {
            var tick = 0
            while (true) {
                try {
                    delay(1000)
                    val entry = activeEntryId
                    if (!destroyed && entry != null && !loadPending && loadedEntryUri == currentUri) {
                        val engineEntry = engineEntryId()
                        val nativePosition = MPVLib.getPropertyDouble("time-pos") ?: continue
                        if (!nativePosition.isFinite() || nativePosition < 0) continue
                        var pos = nativePosition
                        val dur = MPVLib.getPropertyDouble("duration")?.takeIf { it.isFinite() && it > 0 } ?: lastDuration
                        if (engineEntry == entry && engineEntryId() == entry) {
                            pos = requestedSeek.sample(nativePosition)
                            if (!isScrubbing) {
                                position = pos
                                duration = dur
                            }
                            lastPosition = pos
                            if (dur.isFinite() && dur > 0) lastDuration = dur
                            val pausedProp = MPVLib.getPropertyBoolean("pause")
                            if (pausedProp != null) isPaused = pausedProp
                            if (!playbackIntent.canPlay && !isPaused) applyPlaybackGate()
                            if (++tick % 3 == 0) refreshTracks()
                            if (tick % 5 == 0) persistProgress(pos, dur, currentUri)
                        }
                    }
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

    private fun persistProgress(pos: Double, dur: Double, uri: String?, completed: Boolean = false) {
        if (uri == null || pos < 0 || !pos.isFinite() || !dur.isFinite()) return
        LibraryWork.saveProgress(applicationContext, uri, pos, dur.coerceAtLeast(0.0), System.currentTimeMillis(), completed)
    }

    private fun applyPlaybackGate() {
        isPaused = !playbackIntent.canPlay
        if (initialized) mpv(if (isPaused) "일시정지" else "재생") {
            MPVLib.setPropertyBoolean("pause", isPaused)
        }
        updateMediaSessionState()
    }

    private fun setUserPlayback(play: Boolean) {
        if (play) {
            playbackIntent.play()
            if (!playbackIntent.hasFocus) requestAudioFocus()
        } else {
            playbackIntent.pause()
            restoreHeldSpeed()
        }
        applyPlaybackGate()
    }

    private fun togglePlayPause() {
        setUserPlayback(if (playbackIntent.wantsPlay && !playbackIntent.hasFocus) false else isPaused)
        showHud(HudMode.PLAY_PAUSE, if (isPaused) "⏸ 일시정지" else "▶ 재생")
        resetControlsTimer()
    }

    private fun seekTo(target: Double) {
        if (!target.isFinite() || target < 0 || loadPending) return
        mpv("seek absolute $target") {
            MPVLib.command(arrayOf("seek", target.toString(), "absolute+exact"))
            requestedSeek.request(target)
            position = target
            lastPosition = target
        }
    }

    private fun captureCurrentProgress(completed: Boolean = false) {
        if (destroyed || loadPending) return
        val uri = loadedEntryUri ?: return
        if (uri != currentUri) return
        val entry = activeEntryId
        // The engine can already be on another playlist entry when an old callback arrives.
        // Only a matching identity may contribute a fresh sample; otherwise use the last
        // valid sample for this loaded URI (cleared when a new source is installed).
        runCatching {
            if (initialized && entry != null && engineEntryId() == entry) {
                val pos = MPVLib.getPropertyDouble("time-pos")
                val dur = MPVLib.getPropertyDouble("duration")
                if (engineEntryId() == entry) {
                    if (pos != null && pos.isFinite() && pos >= 0) lastPosition = requestedSeek.sample(pos)
                    if (dur != null && dur.isFinite() && dur > 0) lastDuration = dur
                }
            }
        }.onFailure { AppLog.w(TAG, "final progress sample failed: ${it.message}") }
        if (completed && lastDuration.isFinite() && lastDuration > 0) {
            lastPosition = lastDuration
            position = lastPosition
            duration = lastDuration
        }
        persistProgress(lastPosition, lastDuration, uri, completed)
    }

    private fun advance() {
        if (index >= uris.size - 1) return
        captureCurrentProgress()
        index += 1
        beginManualSwitch(index)
        resetControlsTimer()
    }

    private fun previousFile() {
        if (index <= 0) return
        captureCurrentProgress()
        index -= 1
        beginManualSwitch(index)
        resetControlsTimer()
    }

    private fun engineEntryId(): Long? {
        val playing = MPVLib.getPropertyString("playlist-playing-pos")?.toLongOrNull()
            ?.takeIf { it >= 0 } ?: return null
        return MPVLib.getPropertyString("playlist/$playing/id")?.toLongOrNull()
    }

    private fun beginManualSwitch(target: Int, restart: Boolean = false) {
        // Invalidate all event identities before source resolution can suspend.
        rejectedEntryId = activeEntryId ?: engineEntryId()
        loadGeneration++
        activeEntryId = null
        loadPending = true
        loadSubmitted = false
        loadedEntryUri = null
        val generation = loadGeneration
        lifecycleScope.launch {
            val v = playerView ?: return@launch
            val src = loadSource(target, generation, restart) ?: return@launch
            if (generation != loadGeneration || destroyed) {
                src.close()
                return@launch
            }
            loadSubmitted = true
            applyPlaybackGate()
            v.playFile(src.path)
            updateMediaSessionMetadata()
            updateMediaSessionState()
        }
    }

    private fun previous() {
        if (position > 3.0) {
            seekTo(0.0)
            setUserPlayback(true)
            showHud(HudMode.SEEK, "처음부터 재생 (0:00)")
            updateMediaSessionState()
        } else if (index > 0) {
            previousFile()
            return
        }
        resetControlsTimer()
    }

    // ---------------------------------------------------------------- MediaSession & AudioFocus (Bluetooth / Car AV)

    private fun initMediaSession() {
        val session = MediaSession(this, "MoVo")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                AppLog.i(TAG, "MediaSession: onPlay (Bluetooth/Car command)")
                runOnUiThread {
                    setUserPlayback(true)
                }
            }

            override fun onPause() {
                AppLog.i(TAG, "MediaSession: onPause (Bluetooth/Car command)")
                runOnUiThread {
                    setUserPlayback(false)
                }
            }

            override fun onStop() {
                AppLog.i(TAG, "MediaSession: onStop (Bluetooth/Car power off/stop)")
                runOnUiThread {
                    setUserPlayback(false)
                }
            }

            override fun onSkipToNext() {
                AppLog.i(TAG, "MediaSession: onSkipToNext")
                runOnUiThread { advance() }
            }

            override fun onSkipToPrevious() {
                AppLog.i(TAG, "MediaSession: onSkipToPrevious")
                runOnUiThread { previous() }
            }

            override fun onSeekTo(pos: Long) {
                val sec = (pos / 1000.0).coerceIn(0.0, duration.coerceAtLeast(1.0))
                AppLog.i(TAG, "MediaSession: onSeekTo $sec")
                runOnUiThread {
                    seekTo(sec)
                    updateMediaSessionState()
                }
            }

            override fun onFastForward() {
                runOnUiThread { seekRelative(tapSeekSec) }
            }

            override fun onRewind() {
                runOnUiThread { seekRelative(-tapSeekSec) }
            }
        })
        session.isActive = true
        mediaSession = session
    }

    private fun requestAudioFocus(): Boolean {
        val focusRequest = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    playbackIntent.focus(change == AudioManager.AUDIOFOCUS_GAIN,
                        permanentLoss = change == AudioManager.AUDIOFOCUS_LOSS)
                    if (change != AudioManager.AUDIOFOCUS_GAIN) restoreHeldSpeed()
                    applyPlaybackGate()
                }
            }.build().also { audioFocusRequest = it }
        val result = audioManager.requestAudioFocus(focusRequest)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        playbackIntent.focus(granted)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) playbackIntent.pause()
        return granted
    }

    private fun abandonAudioFocus() {
        runCatching {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val playbackState = if (isPaused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        val playbackSpeed = if (isPaused) 0f else speed.toFloat()
        val actions = (
            PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
        )
        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(playbackState, (position * 1000).toLong(), playbackSpeed)
            .build()
        session.setPlaybackState(state)
    }

    private fun updateMediaSessionMetadata() {
        val session = mediaSession ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, videoTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, videoTitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (duration * 1000).toLong())
            .build()
        session.setMetadata(metadata)
    }

    private fun seekRelative(deltaSec: Double) {
        val maxTarget = if (duration > 0) (duration - 0.5).coerceAtLeast(0.0) else 0.0
        val target = if (duration > 0) (position + deltaSec).coerceIn(0.0, maxTarget) else (position + deltaSec).coerceAtLeast(0.0)
        seekTo(target)
        showHud(HudMode.DOUBLE_TAP, if (deltaSec >= 0) "⏩ +${deltaSec.toInt()}초" else "⏪ ${deltaSec.toInt()}초")
        resetControlsTimer()
    }

    private fun selectAspectRatio(next: AspectRatioMode) {
        currentAspectMode = next
        mpv("aspect ratio") { applyAspectRatio(next) }
        showHud(HudMode.ASPECT, next.title)
    }

    private fun selectVideoAlign(next: VideoAlign) {
        currentVideoAlign = next
        mpv("video-align-y=${next.value}") {
            val result = MPVLib.setOptionString("video-align-y", next.value)
            if (result < 0) AppLog.w(TAG, "video-align-y rejected: ${next.value}")
            else AppLog.i(TAG, "video-align-y applied: ${next.value} (result=$result)")
        }
        lifecycleScope.launch { settings.setVideoAlignY(next.value) }
    }

    private fun applyAspectRatio(next: AspectRatioMode) {
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
    }

    private fun toggleAutoRotate() {
        if (autoRotate) {
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            requestedOrientation = when (rotation) {
                android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            autoRotate = false
            showHud(HudMode.ASPECT, "회전 잠금 (현재 방향 고정)")
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            autoRotate = true
            showHud(HudMode.ASPECT, "자동 회전 켬 (4방향)")
        }
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
        val normalized = ((s * 100.0).roundToInt() / 100.0).coerceIn(0.1, 5.0)
        speed = normalized
        preFastPlaySpeed = normalized
        mpv("speed=$normalized") { MPVLib.setPropertyDouble("speed", normalized) }
        AppLog.i(TAG, "speed set to $normalized")
        lifecycleScope.launch { settings.setDefaultSpeed(normalized) }
        showHud(HudMode.ASPECT, "재생 속도: ${SettingsRepo.formatSpeed(normalized)}")
        resetControlsTimer()
    }

    // Subtitle style: mpv sub-font-size + sub-color, applied live and persisted.
    private fun applySubStyle() {
        val size = subFontSize.roundToInt().toString()
        mpv("자막 스타일 ${size}/${subColorHex}") {
            val r1 = MPVLib.setOptionString("sub-font-size", size)
            val r2 = MPVLib.setOptionString("sub-color", subColorHex)
            if (r1 < 0 || r2 < 0) AppLog.w(TAG, "sub style rejected: size=$size color=$subColorHex")
            else AppLog.i(TAG, "sub style applied: size=$size color=$subColorHex")
        }
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

    private suspend fun addExternalSubtitle(uri: Uri, generation: Long) {
        var opened: MpvPath.Playable? = null
        try {
            val path = withContext(Dispatchers.IO) {
                val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@PlayerActivity, uri)?.name ?: "sub.srt"
                MpvPath.cacheCopy(this@PlayerActivity, uri, name)?.absolutePath
                    ?: MpvPath.open(this@PlayerActivity, uri).also { opened = it }.path
            }
            withContext(Dispatchers.Main) {
                if (destroyed || generation != loadGeneration || loadPending) return@withContext
                mpv("외부 자막 추가") {
                    MPVLib.command(arrayOf("sub-add", path, "select"))
                    opened?.let { subPlayables += it; opened = null }
                    externalSubtitleUris += uri.toString()
                    refreshTracks()
                    showHud(HudMode.ASPECT, "외부 자막 추가됨")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "external subtitle failed: ${e.message}")
        } finally {
            opened?.close()
        }
    }

    private suspend fun restorePlaybackUi(generation: Long) {
        val state = withContext(Dispatchers.Main) {
            restoredPlaybackUi?.takeIf { !destroyed && generation == loadGeneration && !loadPending }
        } ?: return
        for (uri in state.getStringArrayList("session.externalSubs").orEmpty()) {
            addExternalSubtitle(Uri.parse(uri), generation)
        }
        withContext(Dispatchers.Main) {
            if (destroyed || generation != loadGeneration || loadPending) return@withContext
            for (property in arrayOf("aid", "sid", "sub-delay", "audio-delay", "video-zoom")) {
                state.getString("session.$property")?.let { value ->
                    mpv("restore $property") { MPVLib.setPropertyString(property, value) }
                }
            }
            if (state.containsKey("session.brightness")) {
                lastBrightness = state.getFloat("session.brightness")
                window.attributes = window.attributes.apply { screenBrightness = lastBrightness }
            }
            restoredPlaybackUi = null
            refreshTracks()
        }
    }

    // Auto-load same-basename external subtitles from the video folder.
    private fun autoLoadSubtitles() {
        val uriStr = currentUri ?: return
        val generation = loadGeneration
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
                    if (destroyed || generation != loadGeneration || loadPending) return@withContext
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "auto-sub failed: ${e.message}")
            } finally {
                restorePlaybackUi(generation)
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
                .pointerInput(isLocked, tapSeekSec, fastSpeedSetting, gestureEpoch) {
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

                        try {
                        while (true) {
                            val event = if (!gestureDetermined) {
                                val remaining = (500 - (System.currentTimeMillis() - downTime)).coerceAtLeast(1)
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                gestureDetermined = true
                                isFastPlay = true
                                preFastPlaySpeed = speed
                                fastPlayHeld = true
                                val fs = fastSpeedSetting
                                mpv("held fast speed") { MPVLib.setPropertyDouble("speed", fs) }
                                speed = fs
                                showHud(HudMode.FAST_PLAY, "⚡ ${SettingsRepo.formatSpeed(fs)} 쾌속 재생 중", autoDismiss = false)
                                continue
                            }
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
                        if (isSeek && playbackIntent.foreground) {
                            seekTo(currentSeekTarget)
                            showHud(HudMode.SEEK, "이동: ${fmt(currentSeekTarget)}")
                        } else if (isFastPlay) {
                            restoreHeldSpeed()
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
                        } finally {
                            if (isFastPlay) restoreHeldSpeed()
                        }
                    }
                }
        )
    }

    // ---------------------------------------------------------------- Centered Gesture HUD

    @Composable
    private fun BoxScope.GestureHudOverlay() {
        if (hudMode == HudMode.NONE) return
        if (controlsVisible && !isLocked) {
            // Compact feedback stays clear of the primary button's center hit target.
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 80.dp)
                    .heightIn(max = 72.dp)
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    hudText,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            return
        }

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
    private fun ControlsOverlay(onShowTitle: () -> Unit) {
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
            // Title has its own row, so toolbar buttons cannot consume its width.
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
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "전체 영상 제목 보기",
                            onClick = onShowTitle,
                        )
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.Center,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 96.dp).padding(horizontal = 4.dp),
                    )
                }
            }

            // Separate scrollable toolbar keeps every action reachable on narrow screens.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {

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

                // Auto-rotate toggle: ON = 4-way sensor, OFF = lock current orientation
                IconButton(onClick = { toggleAutoRotate() }) {
                    Icon(
                        if (autoRotate) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation, "자동 회전",
                        tint = if (autoRotate) MaterialTheme.colorScheme.primary else Color.White,
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
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(SettingsRepo.formatSpeed(speed), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        seekTo(target)
                        resetControlsTimer()
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "재생 위치"
                        stateDescription = "현재 ${fmt(if (isScrubbing) scrubPosition else position)}, 전체 ${fmt(duration)}"
                    }.heightIn(min = 48.dp),
                    colors = sliderColors,
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(10.dp),
                            colors = sliderColors,
                        )
                    },
                    thumb = {
                        // Slider measures its semantic height from the thumb, not outer padding.
                        Box(Modifier.size(width = 20.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                            SliderDefaults.Thumb(
                                interactionSource = remember { MutableInteractionSource() },
                                modifier = Modifier.size(20.dp),
                                colors = sliderColors,
                            )
                        }
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
                        val remainSec = playbackRemainingSeconds(duration, if (isScrubbing) scrubPosition else position, speed)
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
                            IconButton(onClick = { seekRelative(-tapSeekSec) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Replay10, "${tapSeekSec.toInt()}초 뒤로", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            IconButton(onClick = { seekRelative(tapSeekSec) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Forward10, "${tapSeekSec.toInt()}초 앞으로", tint = Color.White, modifier = Modifier.size(24.dp))
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
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.RepeatOne, "한곡 반복",
                                    tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            OutlinedButton(
                                onClick = {
                                    controlsTimerJob?.cancel()
                                    showDisplayDialog = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.4f)))
                                ),
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "화면 설정: 영상 정렬 및 화면 비율"
                                },
                            ) {
                                Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("화면", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                        val remainSec = playbackRemainingSeconds(duration, if (isScrubbing) scrubPosition else position, speed)
                        val remainTime = "-${fmt(remainSec)}"
                        Text(
                            "$curTime / $durTime ($remainTime)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        IconButton(
                            onClick = { toggleRepeat() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.RepeatOne, "한곡 반복",
                                tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                controlsTimerJob?.cancel()
                                showDisplayDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.4f)))
                            ),
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "화면 설정: 영상 정렬 및 화면 비율"
                            },
                        ) {
                            Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("화면", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Centered Professional Media Playback Controls
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { seekRelative(-tapSeekSec) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Replay10, "${tapSeekSec.toInt()}초 뒤로", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        IconButton(onClick = { seekRelative(tapSeekSec) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Forward10, "${tapSeekSec.toInt()}초 앞으로", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DisplayDialog(onDismiss: () -> Unit) {
        val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtMost(360.dp)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("화면 설정") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("영상 정렬", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("화면에 세로 여백이 있을 때 영상 위치를 조절합니다.", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.selectableGroup()) {
                        VideoAlign.entries.forEach { alignment ->
                            val label = when (alignment) {
                                VideoAlign.TOP -> "위"
                                VideoAlign.CENTER -> "중간"
                                VideoAlign.BOTTOM -> "아래"
                            }
                            DisplayChoice(label, currentVideoAlign == alignment) { selectVideoAlign(alignment) }
                        }
                    }
                    HorizontalDivider()
                    Text("화면 비율", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.selectableGroup()) {
                        AspectRatioMode.entries.forEach { mode ->
                            DisplayChoice(mode.title, currentAspectMode == mode) { selectAspectRatio(mode) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        )
    }

    @Composable
    private fun DisplayChoice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }

    @Composable
    private fun SubtitleColorSwatch(hex: String, name: String, selected: Boolean, onClick: () -> Unit) {
        val color = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
        val hsv = remember(hex) { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) } }
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(40.dp)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check, null,
                            tint = if (hsv[2] > 0.65f && (hsv[1] < 0.4f || hsv[0] in 35f..180f)) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SubtitlePaletteDialog(initialColor: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        val initialHsv = remember {
            FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initialColor), it) }
        }
        var hue by remember { mutableFloatStateOf(initialHsv[0]) }
        var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
        var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
        val hsv = remember { FloatArray(3) }
        val draftArgb = remember(hue, saturation, brightness) {
            hsv[0] = hue
            hsv[1] = saturation
            hsv[2] = brightness
            android.graphics.Color.HSVToColor(hsv)
        }
        val draftHex = remember(draftArgb) {
            String.format(java.util.Locale.ROOT, "#%06X", draftArgb and 0xFFFFFF)
        }
        val draftColor = Color(draftArgb)
        val colors = remember {
            listOf(
                "#FFFFFF" to "흰색", "#E0E0E0" to "밝은 회색", "#808080" to "회색", "#404040" to "진한 회색",
                "#000000" to "검은색", "#FFF4D6" to "크림색", "#FFFF00" to "노란색", "#FFC107" to "황금색",
                "#FF9800" to "주황색", "#FF5252" to "빨간색", "#FF80C0" to "분홍색", "#E040FB" to "자홍색",
                "#B388FF" to "연보라색", "#7C4DFF" to "보라색", "#448AFF" to "파란색", "#80D8FF" to "연한 하늘색",
                "#00FFFF" to "하늘색", "#64FFDA" to "민트색", "#00FF00" to "초록색", "#B2FF59" to "연두색",
            )
        }
        val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtMost(420.dp)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("자막 색상 팔레트") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("색상을 고르거나 아래 슬라이더로 조절하세요.", style = MaterialTheme.typography.bodySmall)
                    Text("선택 색상 미리보기", style = MaterialTheme.typography.titleSmall)
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF202020)).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("자막 미리보기", color = draftColor, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(draftColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .semantics { contentDescription = "선택한 색상" },
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        colors.forEach { (hex, name) ->
                            SubtitleColorSwatch(hex, name, hex.equals(draftHex, true)) {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                            }
                        }
                    }
                    Text("색조", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = hue, onValueChange = { hue = it }, valueRange = 0f..360f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "색조" }.heightIn(min = 48.dp),
                        thumb = {
                            Box(Modifier.size(width = 20.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                                SliderDefaults.Thumb(
                                    interactionSource = remember { MutableInteractionSource() },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                    Text("채도", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "채도" }.heightIn(min = 48.dp),
                        thumb = {
                            Box(Modifier.size(width = 20.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                                SliderDefaults.Thumb(
                                    interactionSource = remember { MutableInteractionSource() },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                    Text("밝기", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = brightness, onValueChange = { brightness = it }, valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "밝기" }.heightIn(min = 48.dp),
                        thumb = {
                            Box(Modifier.size(width = 20.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                                SliderDefaults.Thumb(
                                    interactionSource = remember { MutableInteractionSource() },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onConfirm(draftHex) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("적용") } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
        )
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
        fontSize: Double,
        subColor: String,
        onFontSizeChange: (Double) -> Unit,
        onFontSizeFinal: () -> Unit,
        onSubColor: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val currentSelectedId = tracks.find { it.isSelected }?.id ?: -1
        var showPalette by remember { mutableStateOf(false) }
        val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtMost(360.dp)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Subtitles, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("자막 설정")
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("자막 트랙", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("외부 자막 파일 불러오기")
                    }
                    Text("지원 형식: .srt, .vtt, .ass", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Subtitle style: size + color (applied live, persisted)
                    Text(
                        "글자 크기: ${fontSize.roundToInt()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { onFontSizeChange(it.toDouble()) },
                        onValueChangeFinished = { onFontSizeFinal() },
                        valueRange = 20f..120f,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            contentDescription = "자막 글자 크기"
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("글자 색상", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val names = listOf("흰색", "노란색", "하늘색", "초록색", "분홍색")
                        SettingsRepo.SUB_COLOR_PRESETS.forEachIndexed { index, hex ->
                            SubtitleColorSwatch(hex, names[index], hex.equals(subColor, true)) {
                                onSubColor(hex)
                            }
                        }
                        OutlinedButton(
                            onClick = { showPalette = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.Palette, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("팔레트")
                        }
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
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { onAdjustDelay(-100.0) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("-0.1초")
                        }
                        FilledTonalButton(
                            onClick = onResetDelay,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("초기화")
                        }
                        FilledTonalButton(
                            onClick = { onAdjustDelay(100.0) },
                            modifier = Modifier.heightIn(min = 48.dp),
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
        if (showPalette) {
            SubtitlePaletteDialog(
                initialColor = subColor,
                onConfirm = { color ->
                    onSubColor(color)
                    showPalette = false
                },
                onDismiss = { showPalette = false },
            )
        }
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
        step: Double = SettingsRepo.DEFAULT_SPEED_STEP,
        onSelectSpeed: (Double) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val safeStep = if (step.isFinite() && step in 0.01..1.0) {
            (step * 100.0).roundToInt() / 100.0
        } else {
            SettingsRepo.DEFAULT_SPEED_STEP
        }
        val stepCents = (safeStep * 100.0).roundToInt().coerceIn(1, 100)
        var tempSpeed by remember { mutableDoubleStateOf(currentSpeed.coerceIn(0.1, 5.0)) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("재생 속도 (선택: ${SettingsRepo.formatSpeed(tempSpeed)})")
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
                            val isSel = abs(s - tempSpeed) < 0.001
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    tempSpeed = s
                                },
                                label = {
                                    Text(SettingsRepo.formatSpeed(s))
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "미세 속도 조절 (단위: ±${SettingsRepo.formatSpeed(safeStep)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val curCents = (tempSpeed * 100.0).roundToInt()
                                val newCents = (curCents - stepCents).coerceIn(10, 500)
                                tempSpeed = newCents / 100.0
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Remove, "속도 감소 (-${SettingsRepo.formatSpeed(safeStep)})")
                        }

                        Slider(
                            value = tempSpeed.toFloat().coerceIn(0.1f, 5.0f),
                            onValueChange = { raw ->
                                tempSpeed = when {
                                    raw <= 0.1001f -> 0.1
                                    raw >= 4.9999f -> 5.0
                                    else -> {
                                        val steps = (raw.toDouble() * 100.0 / stepCents).roundToInt()
                                        (steps * stepCents).coerceIn(10, 500) / 100.0
                                    }
                                }
                            },
                            valueRange = 0.1f..5.0f,
                            modifier = Modifier.weight(1f),
                        )

                        IconButton(
                            onClick = {
                                val curCents = (tempSpeed * 100.0).roundToInt()
                                val newCents = (curCents + stepCents).coerceIn(10, 500)
                                tempSpeed = newCents / 100.0
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Add, "속도 증가 (+${SettingsRepo.formatSpeed(safeStep)})")
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
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("${SettingsRepo.formatSpeed(1.0)} (표준 속도)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSelectSpeed(tempSpeed) },
                    modifier = Modifier.heightIn(min = 48.dp),
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
                    setUserPlayback(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    setUserPlayback(false)
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
    override fun startFile(playlistEntryId: Long) {
        if (destroyed) return
        val generation = loadGeneration
        runOnUiThread {
            if (destroyed || isFinishing || generation != loadGeneration || !loadSubmitted) return@runOnUiThread
            val current = engineEntryId()
            if (current != playlistEntryId || playlistEntryId == rejectedEntryId) return@runOnUiThread
            activeEntryId = playlistEntryId
            loadPending = false
        }
    }

    override fun endFile(reason: Int, error: Int, playlistEntryId: Long) {
        if (destroyed) return
        runOnUiThread {
            if (destroyed || isFinishing || activeEntryId != playlistEntryId) return@runOnUiThread
            val endingEntry = activeEntryId
            captureCurrentProgress(
                completed = reason == MPVLib.MpvEndFile.MPV_END_FILE_REASON_EOF && error >= 0
            )
            activeEntryId = null
            when (playbackEndAction(reason, error, playlistEntryId, endingEntry, repeatOne, autoAdvance && index < uris.size - 1)) {
                PlaybackEndAction.REPEAT -> beginManualSwitch(index, restart = true)
                PlaybackEndAction.ADVANCE -> advance()
                PlaybackEndAction.PAUSE -> setUserPlayback(false)
                PlaybackEndAction.ERROR -> {
                    setUserPlayback(false)
                    fail("재생 오류 (error=$error)")
                }
                PlaybackEndAction.IGNORE -> Unit
            }
        }
    }

    override fun event(eventId: Int) {
        if (destroyed) return
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
            val generation = loadGeneration
            runOnUiThread {
                val entry = activeEntryId
                if (destroyed || generation != loadGeneration || loadPending || entry == null || engineEntryId() != entry) return@runOnUiThread
                val nativePosition = MPVLib.getPropertyDouble("time-pos") ?: return@runOnUiThread
                if (nativePosition.isFinite() && nativePosition >= 0 && engineEntryId() == entry) {
                    lastPosition = requestedSeek.confirm(nativePosition, if (isPaused) 0.0 else speed)
                    position = lastPosition
                }
            }
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            runOnUiThread {
                if (destroyed || isFinishing || loadPending || engineEntryId() != activeEntryId) return@runOnUiThread
                val loadedDuration = MPVLib.getPropertyDouble("duration")
                if (loadedDuration != null && loadedDuration.isFinite() && loadedDuration > 0 &&
                    engineEntryId() == activeEntryId
                ) {
                    lastDuration = loadedDuration
                    duration = loadedDuration
                }
                refreshTracks()
                updateMediaSessionMetadata()
                updateMediaSessionState()
                autoLoadSubtitles()
            }
        } else AppLog.i(TAG, "mpv event id=$eventId")
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            AppLog.line("M", prefix, text.trim())
        }
    }

    private fun updateSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible && !isLocked) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        playbackIntent.foreground(true)
        if (playbackIntent.wantsPlay && !playbackIntent.hasFocus) requestAudioFocus()
        applyPlaybackGate()
        updateSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureCurrentProgress()
        restoredSession?.let { outState.putAll(it) }
        restoredPlaybackUi?.let { outState.putAll(it) }
        outState.putInt("session.index", index)
        if (currentUri != null) {
            outState.putString("session.uri", currentUri)
            outState.putDouble("session.position", lastPosition)
            outState.putDouble("session.duration", lastDuration)
        }
        outState.putBoolean("session.play", playbackIntent.wantsPlay)
        outState.putBoolean("session.controls", controlsVisible)
        outState.putBoolean("session.locked", isLocked)
        outState.putBoolean("session.rotate", autoRotate)
        outState.putInt("session.orientation", requestedOrientation)
        outState.putInt("session.aspect", currentAspectMode.ordinal)
        outState.putDouble("session.speed", if (fastPlayHeld) preFastPlaySpeed else speed)
        outState.putBoolean("session.speedDialog", showSpeedDialog)
        outState.putBoolean("session.audioDialog", showAudioDialog)
        outState.putBoolean("session.subDialog", showSubDialog)
        if (initialized && !loadPending && activeEntryId != null) {
            runCatching {
                if (engineEntryId() == activeEntryId) {
                    for (property in arrayOf("aid", "sid", "sub-delay", "audio-delay", "video-zoom")) {
                        MPVLib.getPropertyString(property)?.let { outState.putString("session.$property", it) }
                    }
                }
            }.onFailure { AppLog.w(TAG, "session UI capture failed: ${it.message}") }
            outState.putStringArrayList("session.externalSubs", ArrayList(externalSubtitleUris))
        }
        outState.putFloat("session.brightness", window.attributes.screenBrightness)
        super.onSaveInstanceState(outState)
    }

    private fun restoreHeldSpeed() {
        if (!fastPlayHeld) return
        fastPlayHeld = false
        speed = preFastPlaySpeed
        if (initialized && !destroyed) mpv("restore held speed") { MPVLib.setPropertyDouble("speed", speed) }
        hudMode = HudMode.NONE
    }

    override fun onPause() {
        captureCurrentProgress()
        playbackIntent.foreground(false)
        gestureEpoch++
        restoreHeldSpeed()
        applyPlaybackGate()
        super.onPause()
    }

    override fun onDestroy() {
        captureCurrentProgress()
        destroyed = true
        loadGeneration++
        activeEntryId = null
        if (isFinishing) {
            val token = playlistToken
            val application = applicationContext
            if (token != null) LibraryWork.scope.launch {
                runCatching { PlayerPlaylistStore(application.filesDir.resolve("player-playlists")).remove(token) }
                    .onFailure { AppLog.w(TAG, "playlist cleanup failed: ${it.message}") }
            }
        }
        super.onDestroy()
        pollJob?.cancel()
        controlsTimerJob?.cancel()
        hudJob?.cancel()
        runCatching { playable?.close() }
        playable = null
        subPlayables.forEach { runCatching { it.close() } }
        subPlayables.clear()
        runCatching { noisyReceiver?.let { unregisterReceiver(it) } }
        noisyReceiver = null
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
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
