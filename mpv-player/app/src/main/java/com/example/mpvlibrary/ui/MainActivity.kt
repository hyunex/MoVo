package com.example.mpvlibrary.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.mpvlibrary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        // MANAGE_EXTERNAL_STORAGE 없이 SAF 영속 권한만으로 동작한다.
        // 동영상 삭제는 DocumentsContract/DocumentFile 경로만 사용하고
        // 직접 파일 경로 삭제(File.delete/MediaStore)는 시도하지 않는다.
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.install(this)
        AppLog.i("app", "MainActivity created")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

/** Simple stack navigation: Library -> Folder(path); Settings. Player is its own Activity. */
sealed interface Screen {
    data object Library : Screen
    data class Folder(val folderId: Long, val path: String) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scanner = remember { LibraryScanner(applicationContext) }
    val deleteState = remember {
        SafeDeleteState(applicationContext, AppDb.get(applicationContext), scanner)
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    LaunchedEffect(Unit) {
        scanner.scanAll()
    }
    // P0: next-launch crash report (Next Player style, backed by AppLog file)
    var crashFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    LaunchedEffect(Unit) {
        crashFiles = kotlinx.coroutines.withContext(Dispatchers.IO) { AppLog.pendingCrashReports() }
    }
    if (crashFiles.isNotEmpty()) {
        val latest = crashFiles.last()
        var preview by remember(latest) { mutableStateOf(runCatching { latest.readText().take(3000) }.getOrDefault("(읽기 실패)")) }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("이전 실행에서 앱이 종료됨") },
            text = {
                Column {
                    Text(
                        "크래시 리포트가 저장되었습니다. 공유하여 문제를 제보할 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        preview.ifEmpty { "(내용 없음)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        context.startActivity(Intent.createChooser(AppLog.shareFileIntent(context, latest), "크래시 리포트 공유"))
                    }) { Text("공유") }
                    TextButton(onClick = {
                        AppLog.dismissCrashReports()
                        crashFiles = emptyList()
                    }) { Text("지우기") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLog.dismissCrashReports()
                    crashFiles = emptyList()
                }) { Text("닫기") }
            },
        )
    }
    BackHandler(enabled = screen !is Screen.Library) {
        when (val s = screen) {
            is Screen.Settings -> screen = Screen.Library
            is Screen.Folder -> {
                screen = when {
                    s.path.contains('/') -> Screen.Folder(s.folderId, s.path.substringBeforeLast('/'))
                    s.path.isNotEmpty() -> Screen.Folder(s.folderId, "")
                    else -> Screen.Library
                }
            }
            Screen.Library -> {}
        }
    }

    SafeDeleteDialogs(deleteState)

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            onSettings = { screen = Screen.Settings },
            deleteState = deleteState,
        )
        is Screen.Folder -> FolderScreen(
            folderId = s.folderId, path = s.path,
            deleteState = deleteState,
            onPath = { screen = Screen.Folder(s.folderId, it) },
            onBack = {
                screen = when {
                    s.path.contains('/') -> Screen.Folder(s.folderId, s.path.substringBeforeLast('/'))
                    s.path.isNotEmpty() -> Screen.Folder(s.folderId, "")
                    else -> Screen.Library
                }
            },
            onSettings = { screen = Screen.Settings },
            onFolderDeleted = { screen = Screen.Library },
        )
        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Library })
    }
}

@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            content = content,
        )
    }
}

/**
 * Pull-to-refresh wrapper (material3 1.2 API): drag list down from the top
 * to rescan.
 */
@Composable
fun PullRefreshWrapper(
    modifier: Modifier = Modifier,
    onRefresh: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    if (state.isRefreshing) {
        LaunchedEffect(true) {
            isRefreshing = true
            try {
                onRefresh()
            } finally {
                state.endRefresh()
                isRefreshing = false
            }
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .nestedScroll(state.nestedScrollConnection),
    ) {
        content()
        if (state.verticalOffset > 0f || state.isRefreshing || isRefreshing) {
            PullToRefreshContainer(
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

// ---------------------------------------------------------------- Library

private sealed interface HomeSelection {
    data object ContinueWatching : HomeSelection
    data class Folder(val folderId: Long, val path: String) : HomeSelection
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarItem(
    icon: @Composable () -> Unit,
    label: String,
    badge: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    // Long-press 지원 시 Surface 자체 onClick을 비우고 combinedClickable 하나로 통합
    // (Surface onClick이 제스처를 선점하면 롱프레스가 발동하지 않음).
    @Composable
    fun itemContent() {
        Row(
            modifier = Modifier.sizeIn(minHeight = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                icon()
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null && badge > 0) {
                Badge(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(badge.toString(), fontSize = 11.sp)
                }
            }
        }
    }
    val itemModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .then(
            if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            else Modifier
        )
    if (onLongClick != null) {
        Surface(modifier = itemModifier, shape = RoundedCornerShape(12.dp), color = bg) { itemContent() }
    } else {
        Surface(onClick = onClick, modifier = itemModifier, shape = RoundedCornerShape(12.dp), color = bg) { itemContent() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onSettings: () -> Unit,
    deleteState: SafeDeleteState,
) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scanner = remember { LibraryScanner(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    var recent by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    val scanStatuses by LibraryScanner.statuses.collectAsState()
    var threshold by remember { mutableStateOf(0.9) }
    var thumbScale by remember { mutableStateOf("medium") }
    var continuePlaylistMode by remember { mutableStateOf(SettingsRepo.DEFAULT_CONTINUE_PLAYLIST) }
    var selection by remember { mutableStateOf<HomeSelection>(HomeSelection.ContinueWatching) }
    var folderPath by remember { mutableStateOf("") }
    var unregisterTarget by remember { mutableStateOf<FolderEntity?>(null) }

    fun requestFolderUnregister(f: FolderEntity) { unregisterTarget = f }

    // Back: sub-folder -> folder root -> 이어보기 (so every entry/exit path stays reachable).
    BackHandler(enabled = selection is HomeSelection.Folder) {
        if (folderPath.isNotEmpty()) {
            folderPath = if (folderPath.contains('/')) folderPath.substringBeforeLast('/') else ""
        } else {
            selection = HomeSelection.ContinueWatching
        }
    }

    LaunchedEffect(Unit) {
        val s = SettingsRepo(context)
        threshold = s.watchedThreshold.first()
        thumbScale = s.thumbScale.first()
        launch(Dispatchers.IO) { db.folders().observeAll().collect { folders = it } }
        launch(Dispatchers.IO) { db.videos().observeRecent(10).collect { recent = it } }
        launch(Dispatchers.IO) { s.thumbScale.collect { thumbScale = it } }
        launch(Dispatchers.IO) { s.continuePlaylistMode.collect { continuePlaylistMode = it } }
    }

    // Keep selection valid: default to 이어보기, drop removed folders, reset stale sub-paths.
    LaunchedEffect(folders) {
        if (folders.isEmpty()) {
            selection = HomeSelection.ContinueWatching
            folderPath = ""
        } else {
            val sel = selection
            if (sel is HomeSelection.Folder && folders.none { it.id == sel.folderId }) {
                selection = HomeSelection.ContinueWatching
                folderPath = ""
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            LibraryWork.scope.launch {
                val folder = scanner.register(uri)
                scanner.scan(folder, retryMetadata = true)
            }
        }
    }

    // 중단 지점이 있는 미시청 영상: 탭하면 PlayerActivity가 start 옵션으로 이어 재생
    val continueWatching = remember(recent, threshold) {
        recent.filter { it.positionSec > 0 && !it.isWatched(threshold) }.take(6)
    }

    val playContinueVideo: (VideoEntity) -> Unit = { v ->
        scope.launch(Dispatchers.IO) {
            val mode = ContinuePlaylistMode.fromValue(continuePlaylistMode)
            val folderVideos = if (mode == ContinuePlaylistMode.ORIGINAL_FOLDER) {
                db.videos().forFolder(v.folderId)
            } else {
                emptyList()
            }
            val (uris, idx) = buildContinuePlaylist(
                mode = mode,
                target = v,
                continueWatchingList = continueWatching,
                folderVideos = folderVideos,
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                PlayerActivity.start(context, uris, idx)
            }
        }
    }


    BoxWithConstraints(Modifier.fillMaxSize()) {

        val isWide = maxWidth >= 600.dp

        if (isWide) {
            // WIDE SCREEN (Foldable unfolded / Tablet): Permanent Left Sidebar + Right Content
            Row(Modifier.fillMaxSize()) {
                // 1. LEFT SIDEBAR (240.dp)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(240.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    // Header: Brand & Add Folder Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "MoVo",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { picker.launch(null) }) {
                            Icon(Icons.Default.Add, "영상 폴더 등록", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 이어보기 Navigation Item
                    SidebarItem(
                        icon = { Icon(Icons.Default.PlayCircle, null) },
                        label = "이어보기",
                        badge = continueWatching.size.takeIf { it > 0 },
                        selected = selection is HomeSelection.ContinueWatching,
                        onClick = { selection = HomeSelection.ContinueWatching },
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp, horizontal = 8.dp))

                    // Registered Folders Section Header
                    Text(
                        "비디오 폴더",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    LazyColumn(Modifier.weight(1f)) {
                        items(folders, key = { it.id }) { f ->
                            val selected = selection is HomeSelection.Folder &&
                                (selection as HomeSelection.Folder).folderId == f.id
                            val status = scanStatuses[f.id]
                            Column {
                                SidebarItem(
                                    icon = { Icon(Icons.Default.Folder, null) },
                                    label = f.displayName,
                                    badge = null,
                                    selected = selected,
                                    onClick = {
                                        folderPath = ""
                                        selection = HomeSelection.Folder(f.id, "")
                                    },
                                    onLongClick = { requestFolderUnregister(f) },
                                )
                                if (status?.running == true) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                                } else if (status?.error != null) {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("스캔 실패", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scan(f, retryMetadata = true) } }) { Text("재시도") }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 8.dp))

                    // Settings Button
                    SidebarItem(
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = "설정",
                        badge = null,
                        selected = false,
                        onClick = onSettings,
                    )
                }

                VerticalDivider()

                // 2. RIGHT CONTENT PANE (Single TopAppBar)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val sel = selection) {
                        is HomeSelection.ContinueWatching -> {
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text("이어보기", fontWeight = FontWeight.Bold) },
                                        actions = {
                                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll(retryMetadata = true) } }) {
                                                Icon(Icons.Default.Refresh, "스캔")
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                                    )
                                },
                            ) { pad ->
                                ContinueWatchingPane(
                                    videos = continueWatching,
                                    threshold = threshold,
                                    onPlay = playContinueVideo,
                                    onRefresh = { scanner.scanAll(retryMetadata = true) },
                                    deleteState = deleteState,
                                    modifier = Modifier.padding(pad),
                                    thumbSize = when (thumbScale) {
                                        "small" -> 88.dp
                                        "large" -> 148.dp
                                        else -> 116.dp
                                    },
                                )
                            }
                        }
                        is HomeSelection.Folder -> {
                            key(sel.folderId, folderPath) {
                                FolderScreen(
                                    folderId = sel.folderId,
                                    path = folderPath,
                                    deleteState = deleteState,
                                    onPath = { folderPath = it },
                                    onBack = if (folderPath.isNotEmpty()) {
                                        {
                                            folderPath = if (folderPath.contains('/')) folderPath.substringBeforeLast('/')
                                            else ""
                                        }
                                    } else null,
                                    onSettings = onSettings,
                                    showTopBar = true,
                                    onFolderDeleted = {
                                        selection = HomeSelection.ContinueWatching
                                        folderPath = ""
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // NARROW SCREEN (Portrait Phone): Single TopAppBar + Horizontal Tabs
            val sel = selection
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            if (sel is HomeSelection.Folder) {
                                IconButton(onClick = {
                                    if (folderPath.isNotEmpty()) {
                                        folderPath = if (folderPath.contains('/')) folderPath.substringBeforeLast('/') else ""
                                    } else {
                                        selection = HomeSelection.ContinueWatching
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기")
                                }
                            }
                        },
                        title = {
                            when (val s = selection) {
                                is HomeSelection.ContinueWatching -> Text("MoVo", fontWeight = FontWeight.Bold)
                                is HomeSelection.Folder -> {
                                    val curF = folders.find { it.id == s.folderId }
                                    val fName = curF?.displayName ?: "폴더"
                                    val displayTitle = if (folderPath.isEmpty()) fName else "$fName / ${folderPath.substringAfterLast('/')}"
                                    Text(displayTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { picker.launch(null) }) {
                                Icon(Icons.Default.CreateNewFolder, "영상 폴더 등록")
                            }
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll(retryMetadata = true) } }) {
                                Icon(Icons.Default.Refresh, "스캔")
                            }
                            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "설정") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    // Horizontal navigation tabs for 1-tap switching between 이어보기 & folders
                    ScrollableTabRow(
                        selectedTabIndex = when (sel) {
                            is HomeSelection.ContinueWatching -> 0
                            is HomeSelection.Folder -> 1 + folders.indexOfFirst { it.id == sel.folderId }.coerceAtLeast(0)
                        },
                        edgePadding = 16.dp,
                    ) {
                        Tab(
                            selected = sel is HomeSelection.ContinueWatching,
                            onClick = { selection = HomeSelection.ContinueWatching },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("이어보기")
                                    if (continueWatching.isNotEmpty()) {
                                        Spacer(Modifier.width(4.dp))
                                        Text("(${continueWatching.size})", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                        )
                        folders.forEach { f ->
                            Tab(
                                selected = sel is HomeSelection.Folder && sel.folderId == f.id,
                                onClick = {
                                    folderPath = ""
                                    selection = HomeSelection.Folder(f.id, "")
                                },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                folderPath = ""
                                                selection = HomeSelection.Folder(f.id, "")
                                            },
                                            onLongClick = { requestFolderUnregister(f) },
                                        ),
                                    ) {
                                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(f.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                            )
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (val s = selection) {
                            is HomeSelection.ContinueWatching -> ContinueWatchingPane(
                                videos = continueWatching,
                                threshold = threshold,
                                onPlay = playContinueVideo,
                                onRefresh = { scanner.scanAll(retryMetadata = true) },
                                deleteState = deleteState,
                                thumbSize = when (thumbScale) {
                                    "small" -> 88.dp
                                    "large" -> 148.dp
                                    else -> 116.dp
                                },
                            )
                            is HomeSelection.Folder -> {
                                key(s.folderId, folderPath) {
                                    FolderScreen(
                                        folderId = s.folderId,
                                        path = folderPath,
                                        deleteState = deleteState,
                                        onPath = { folderPath = it },
                                        onBack = null,
                                        onSettings = onSettings,
                                        showTopBar = false,
                                        onFolderDeleted = {
                                            selection = HomeSelection.ContinueWatching
                                            folderPath = ""
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (unregisterTarget != null) {
        val target = unregisterTarget!!
        AlertDialog(
            onDismissRequest = { unregisterTarget = null },
            title = { Text("폴더 등록 해제") },
            text = { Text("\"${target.displayName}\" 폴더를 라이브러리에서 제외합니다. 실제 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    unregisterTarget = null
                    LibraryWork.scope.launch {
                        scanner.unregister(target)
                    }
                }) { Text("해제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { unregisterTarget = null }) { Text("취소") }
            },
        )
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentRow(v: VideoEntity, threshold: Double, onClick: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(v.uri, v.sizeBytes, v.lastModified, v.metadataChecked) {
        VideoMetadata.ensure(context.applicationContext, v)
    }
    val watched = v.isWatched(threshold)
    val rowThumbWidth = adaptiveThumbnailWidth(116.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "${v.name} 재생", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = videoAnnouncement(v)
                stateDescription = videoPlaybackState(v, threshold)
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumb(
            video = v,
            modifier = Modifier
                .size(rowThumbWidth, rowThumbWidth * 9f / 16f)
                .clip(RoundedCornerShape(8.dp)),
            isWatched = watched,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Text(
                v.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            MediaDetails(v)
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (v.dirPath.isNotEmpty()) {
                    Text(v.dirPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    pct(v.fraction) + if (watched) " ✓" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (watched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                if (v.durationSec > 0) {
                    Text(fmtTime(v.positionSec), softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("/ ${fmtTime(v.durationSec)}", softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

data class DeleteTarget(val video: VideoEntity, val parentPath: String)

class SafeDeleteState(
    context: Context,
    private val db: AppDb,
    private val scanner: LibraryScanner,
) {
    private val context = context.applicationContext
    var onDeleteSuccess: ((List<String>) -> Unit)? = null
    var showDeleteDialog by mutableStateOf(false)
    var deleteTargets by mutableStateOf<List<DeleteTarget>>(emptyList())
    var pendingDeleteTargets by mutableStateOf<List<DeleteTarget>>(emptyList())
    var showPermissionLostDialog by mutableStateOf(false)
    var reauthTarget by mutableStateOf<FolderEntity?>(null)

    fun requestDelete(videos: List<VideoEntity>) {
        val snapshots = videos.distinctBy { it.uri }
        if (snapshots.isEmpty()) return
        LibraryWork.scope.launch {
            val folderNames = snapshots.map { it.folderId }.distinct().associateWith {
                db.folders().byId(it)?.displayName ?: "등록 폴더"
            }
            val targets = snapshots.map { video ->
                DeleteTarget(video, "${folderNames.getValue(video.folderId)}/${video.dirPath}".trimEnd('/') + "/")
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                deleteTargets = targets
                showDeleteDialog = true
            }
        }
    }

    fun executeConfirmedDelete() {
        val snapshots = deleteTargets.toList()
        val targets = snapshots.map { it.video.uri }
        if (targets.isEmpty()) return
        showDeleteDialog = false
        deleteTargets = emptyList()

        LibraryWork.scope.launch {
            val entities = snapshots.map { it.video }
            val folderIds = entities.map { it.folderId }.distinct()
            val folders = folderIds.mapNotNull { db.folders().byId(it) }
            val foldersById = folders.associateBy { it.id }
            val entitiesByUri = entities.associateBy { it.uri }

            // Check if any folder lost write permission
            for (f in folders) {
                val treeUri = runCatching { Uri.parse(f.treeUri) }.getOrNull()
                if (treeUri == null || !LibraryScanner.hasPersistedPermission(context, treeUri, write = true)) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        pendingDeleteTargets = snapshots
                        reauthTarget = f
                        showPermissionLostDialog = true
                    }
                    return@launch
                }
            }

            var deletedCount = 0
            var failedCount = 0
            val failedUris = mutableListOf<String>()
            val securityDeniedUris = mutableSetOf<String>()

            for (entity in entities) {
                val u = entity.uri
                val parsed = Uri.parse(u)
                val f = foldersById[entity.folderId]
                if (f != null) {
                    runCatching { LibraryScanner.takePermission(context, Uri.parse(f.treeUri)) }
                }
                var success = false
                if (!success) {
                    val contractRes = runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, parsed)
                    }
                    if (contractRes.isSuccess && contractRes.getOrNull() == true) {
                        success = true
                    } else if (contractRes.exceptionOrNull() is SecurityException) {
                        securityDeniedUris.add(u)
                    }
                }
                if (!success) {
                    val docRes = runCatching {
                        DocumentFile.fromSingleUri(context, parsed)?.delete() == true
                    }
                    if (docRes.isSuccess && docRes.getOrNull() == true) {
                        success = true
                    } else if (docRes.exceptionOrNull() is SecurityException) {
                        securityDeniedUris.add(u)
                    }
                }
                if (!success) {
                    val treeRes = runCatching {
                        if (f != null) {
                            val root = DocumentFile.fromTreeUri(context, Uri.parse(f.treeUri))
                            if (root != null) {
                                var dir: DocumentFile? = root
                                if (entity.dirPath.isNotEmpty()) {
                                    for (seg in entity.dirPath.split('/')) {
                                        if (seg.isEmpty() || seg == "." || seg == "..") continue
                                        dir = dir?.findFile(seg)
                                        if (dir == null) break
                                    }
                                }
                                dir?.findFile(entity.name)?.delete() == true
                            } else false
                        } else false
                    }
                    if (treeRes.isSuccess && treeRes.getOrNull() == true) {
                        success = true
                    } else if (treeRes.exceptionOrNull() is SecurityException) {
                        securityDeniedUris.add(u)
                    }
                }
                if (success) {
                    deletedCount++
                    AppLog.i("library", "file deleted via SAF: $u")
                } else {
                    failedCount++
                    failedUris.add(u)
                    AppLog.w("library", "file SAF delete failed: $u")
                }
            }

            val successfullyDeleted = targets - failedUris.toSet()
            if (successfullyDeleted.isNotEmpty()) {
                db.videos().deleteByUris(successfullyDeleted)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onDeleteSuccess?.invoke(successfullyDeleted)
                }
            }

            if (failedCount > 0) {
                val lostFolder = failedUris.firstNotNullOfOrNull { u ->
                    val v = entitiesByUri.getValue(u)
                    foldersById[v.folderId]?.takeIf { f ->
                        val t = runCatching { Uri.parse(f.treeUri) }.getOrNull()
                        (u in securityDeniedUris) || t == null || !LibraryScanner.hasPersistedPermission(context, t, write = true)
                    }
                }
                if (lostFolder != null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        pendingDeleteTargets = snapshots.filter { it.video.uri in failedUris }
                        reauthTarget = lostFolder
                        showPermissionLostDialog = true
                    }
                } else {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "${deletedCount}개 삭제 완료 (${failedCount}개 실패: 폴더 접근 권한을 확인해 주세요)",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } else {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "${deletedCount}개의 동영상이 삭제되었습니다.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun handlePickerResult(uri: Uri?) {
        AppLog.i("library", "reauth picker result: ${uri != null}")
        if (uri == null) {
            pendingDeleteTargets = emptyList()
            reauthTarget = null
            return
        }
        val target = reauthTarget ?: return

        val targetUri = runCatching { Uri.parse(target.treeUri) }.getOrNull()
        val targetDocId = targetUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        val pickedDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

        val sameAuthority = targetUri != null && uri.authority == targetUri.authority
        val isMatch = (uri.toString() == target.treeUri) ||
                (sameAuthority && targetDocId != null && pickedDocId != null && targetDocId == pickedDocId)

        if (!isMatch) {
            AppLog.w("library", "Picker returned different folder: $uri vs target ${target.treeUri}")
            android.widget.Toast.makeText(
                context,
                "선택한 폴더가 기존 등록된 폴더(\"${target.displayName}\")와 일치하지 않습니다. 올바른 폴더를 다시 선택해 주세요.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            showPermissionLostDialog = true
            return
        }
        LibraryScanner.takePermission(context, uri)
        AppLog.i(
            "library",
            "reauth grant persisted=" +
                LibraryScanner.hasPersistedPermission(context, uri, write = true),
        )
        val retry = pendingDeleteTargets.toList()
        pendingDeleteTargets = emptyList()
        LibraryWork.scope.launch {
            scanner.scan(target, retryMetadata = true)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                reauthTarget = null
                if (retry.isNotEmpty()) {
                    deleteTargets = retry
                    showDeleteDialog = true
                }
            }
        }
    }
}

@Composable
fun SafeDeleteDialogs(state: SafeDeleteState) {
    val treePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        state.handlePickerResult(uri)
    }

    if (state.showDeleteDialog && state.deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                state.showDeleteDialog = false
                state.deleteTargets = emptyList()
            },
            title = { Text("동영상 삭제") },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "warning") {
                        Text(
                            "${state.deleteTargets.size}개의 동영상을 라이브러리 및 저장공간에서 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
                        )
                    }
                    items(state.deleteTargets, key = { it.video.uri }) { target ->
                        Column {
                            Text(target.video.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                target.parentPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { state.executeConfirmedDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.showDeleteDialog = false
                    state.deleteTargets = emptyList()
                }) {
                    Text("취소")
                }
            },
        )
    }

    if (state.showPermissionLostDialog) {
        val target = state.reauthTarget
        val targetName = target?.displayName
        AlertDialog(
            onDismissRequest = {
                state.showPermissionLostDialog = false
                state.reauthTarget = null
                state.pendingDeleteTargets = emptyList()
            },
            icon = { Icon(Icons.Default.FolderShared, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("폴더 접근 권한 필요") },
            text = {
                Text(
                    (if (targetName != null) "\"${targetName}\" 폴더의 접근 권한이 회수되어 파일을 삭제할 수 없습니다.\n\n"
                    else "등록된 폴더의 접근 권한이 회수되어 파일을 삭제할 수 없습니다.\n\n") +
                        "[폴더 다시 선택]을 누르면 문제가 있는 폴더를 바로 보여주니, " +
                        "해당 폴더에서 [이 폴더 사용]을 눌러 권한을 다시 허용해 주세요. " +
                        "실제 파일은 건드리지 않고 권한만 다시 얻은 뒤 삭제 절차를 이어갑니다.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    state.showPermissionLostDialog = false
                    val initial = target?.let { runCatching { Uri.parse(it.treeUri) }.getOrNull() }
                    treePermissionLauncher.launch(initial)
                }) {
                    Text("폴더 다시 선택")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.showPermissionLostDialog = false
                    state.reauthTarget = null
                    state.pendingDeleteTargets = emptyList()
                }) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
fun ContinueWatchingPane(
    videos: List<VideoEntity>,
    threshold: Double,
    onPlay: (VideoEntity) -> Unit,
    onRefresh: suspend () -> Unit,
    deleteState: SafeDeleteState,
    modifier: Modifier = Modifier,
    thumbSize: Dp = 116.dp,
) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scope = rememberCoroutineScope()
    val scanStatuses by LibraryScanner.statuses.collectAsState()
    val registeredFolders by remember(db) { db.folders().observeAll() }.collectAsState(initial = emptyList())
    val failedFolders = remember(registeredFolders, scanStatuses) {
        registeredFolders.filter { scanStatuses[it.id]?.error != null }
    }

    PullRefreshWrapper(
        modifier = modifier.fillMaxSize(),
        onRefresh = onRefresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (failedFolders.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${failedFolders.size}개 폴더 스캔 실패. 기존 목록과 시청 기록은 유지됩니다.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { scope.launch { onRefresh() } }) { Text("재시도") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
        if (videos.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text("이어볼 영상이 없습니다", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(videos, key = { "c" + it.uri }) { v ->
                    val watched = v.isWatched(threshold)
                    VideoRow(
                        v = v,
                        threshold = threshold,
                        isSelected = false,
                        inSelectionMode = false,
                        thumbWidth = thumbSize,
                        onClick = { onPlay(v) },
                        onLongClick = { onPlay(v) },
                        onActionWatched = {
                            scope.launch(Dispatchers.IO) {
                                db.videos().setOverride(v.uri, if (watched) -1 else 1)
                            }
                        },
                        onActionReset = {
                            scope.launch(Dispatchers.IO) {
                                db.videos().resetProgressBatch(listOf(v.uri))
                            }
                        },
                        onActionDelete = {
                            deleteState.requestDelete(listOf(v))
                        },
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
            }
        }
            }
        }
    }
}

// ---------------------------------------------------------------- Folder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolderScreen(
    folderId: Long,
    path: String,
    onPath: (String) -> Unit,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    deleteState: SafeDeleteState,
    showTopBar: Boolean = true,
    onFolderDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scanner = remember { LibraryScanner(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<FolderEntity?>(null) }
    var videos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var threshold by remember { mutableStateOf(0.9) }
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(true) }
    var unseenOnly by remember { mutableStateOf(false) }
    val scanStatus by LibraryScanner.statuses.collectAsState()
    var thumbScale by remember { mutableStateOf("medium") }

    // Multi-selection state for library file management
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    val inSelectionMode = selectedUris.isNotEmpty()
    var treeWriteLost by remember { mutableStateOf(false) }
    val deleteSuccessCallback: (List<String>) -> Unit = remember {
        { deleted -> selectedUris = selectedUris - deleted.toSet() }
    }
    DisposableEffect(deleteState, deleteSuccessCallback) {
        deleteState.onDeleteSuccess = deleteSuccessCallback
        onDispose {
            if (deleteState.onDeleteSuccess === deleteSuccessCallback) {
                deleteState.onDeleteSuccess = null
            }
        }
    }
    val folderDeletedCallback = remember { mutableStateOf<(() -> Unit)?>(null) }
    SideEffect { folderDeletedCallback.value = onFolderDeleted }
    DisposableEffect(Unit) {
        onDispose { folderDeletedCallback.value = null }
    }

    BackHandler(enabled = inSelectionMode) {
        selectedUris = emptySet()
    }

    LaunchedEffect(folderId) {
        val s = SettingsRepo(context)
        threshold = s.watchedThreshold.first()
        thumbScale = s.thumbScale.first()
        launch(Dispatchers.IO) { folder = db.folders().byId(folderId) }
        launch(Dispatchers.IO) { db.videos().observeFolder(folderId).collect { videos = it } }
        launch(Dispatchers.IO) { s.thumbScale.collect { thumbScale = it } }
    }

    val title = folder?.displayName ?: "…"
    val crumbs = if (path.isEmpty()) listOf(title) else listOf(title) + path.split('/')

    val videosByDir = remember(videos) { videos.groupBy { it.dirPath } }
    val naturalNameKeys = remember(videos) { videos.associate { it.uri to naturalKey(it.name) } }
    val subDirs = remember(videosByDir, path) {
        val prefix = if (path.isEmpty()) "" else "$path/"
        videosByDir.keys.asSequence()
            .filter { it.startsWith(prefix) && it != path }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }
    val here = remember(videosByDir, naturalNameKeys, path, query, unseenOnly, sortByName, threshold) {
        val filtered = videosByDir[path].orEmpty().filter {
            (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
                (!unseenOnly || !it.isWatched(threshold))
        }
        if (sortByName) filtered.sortedBy { naturalNameKeys.getValue(it.uri) }
        else filtered.sortedByDescending { it.lastPlayedAt }
    }
    val allUris = remember(here) { here.map { it.uri } }

    val toggleSelect = { uri: String ->
        selectedUris = if (selectedUris.contains(uri)) selectedUris - uri else selectedUris + uri
    }

    // 폴더 트리 쓰기 권한 회수 감지: 삭제 진입 전에도 배너로 재요청을 안내한다.
    LaunchedEffect(folder) {
        val t = folder?.let { runCatching { Uri.parse(it.treeUri) }.getOrNull() }
        treeWriteLost = if (t != null) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                !LibraryScanner.hasPersistedPermission(context, t, write = true)
            }
        } else false
    }

    val folderContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.fillMaxSize()) {
            val status = scanStatus[folderId]
            if (status?.running == true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (status?.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("폴더 스캔 실패. 기존 영상 목록은 유지됩니다.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { folder?.let { scanner.scan(it, retryMetadata = true) } } }) { Text("재시도") }
                    }
                }
            }
            if (treeWriteLost) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "폴더 접근 권한이 회수되어 삭제·갱신이 안 될 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            deleteState.pendingDeleteTargets = emptyList()
                            deleteState.reauthTarget = folder
                            deleteState.showPermissionLostDialog = true
                        }) { Text("권한 다시 허용") }
                    }
                }
            }
            // If in selection mode and showTopBar is false (narrow screen), show compact action banner
            if (inSelectionMode && !showTopBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { selectedUris = emptySet() }) {
                            Icon(Icons.Default.Close, "선택 취소")
                        }
                        Text(
                            "${selectedUris.size}개 선택",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        val allSelected = here.isNotEmpty() && selectedUris.size == here.size
                        IconButton(onClick = {
                            selectedUris = if (allSelected) emptySet() else here.map { it.uri }.toSet()
                        }) {
                            Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, "모두 선택")
                        }
                        IconButton(onClick = {
                            val list = here.filter { selectedUris.contains(it.uri) }.map { it.uri }
                            if (list.isNotEmpty()) PlayerActivity.start(context, list, 0)
                        }) {
                            Icon(Icons.Default.PlayArrow, "선택 재생", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            deleteState.requestDelete(videos.filter { it.uri in selectedUris })
                        }) {
                            Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Search & Filter controls
            BoxWithConstraints(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val compactControls = maxWidth < 360.dp || maxWidth.value / LocalDensity.current.fontScale < 300f
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("파일명 검색", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = (if (compactControls) Modifier.fillMaxWidth() else Modifier.weight(1f))
                            .align(Alignment.CenterVertically),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "지우기")
                            }
                        },
                    )
                    IconButton(
                        onClick = { sortByName = !sortByName },
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Icon(if (sortByName) Icons.Default.SortByAlpha else Icons.Default.History, "정렬")
                    }
                    FilterChip(
                        selected = unseenOnly, onClick = { unseenOnly = !unseenOnly },
                        label = { Text("미시청", maxLines = 1, softWrap = false) },
                        modifier = Modifier.align(Alignment.CenterVertically),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            // Videos: list only.
            val thumbSize: Dp = when (thumbScale) {
                "small" -> 88.dp
                "large" -> 148.dp
                else -> 116.dp
            }
            PullRefreshWrapper(
                modifier = Modifier.weight(1f),
                onRefresh = { scanner.scanAll(retryMetadata = true) },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (subDirs.isNotEmpty()) {
                        items(subDirs, key = { "d$folderId$it" }) { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPath(if (path.isEmpty()) d else "$path/$d") }
                                    .sizeIn(minHeight = 48.dp)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.SubdirectoryArrowRight, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(d, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        }
                    }
                    if (subDirs.isEmpty() && here.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (path.isEmpty()) "영상이 없습니다. 새로고침(⟳)해 보세요." else "이 폴더에 영상이 없습니다.",
                                    color = Color.Gray,
                                )
                            }
                        }
                    } else {
                        items(here, key = { it.uri }) { v ->
                            val isSelected = selectedUris.contains(v.uri)
                            VideoRow(
                                v = v,
                                threshold = threshold,
                                isSelected = isSelected,
                                inSelectionMode = inSelectionMode,
                                thumbWidth = thumbSize,
                                onClick = {
                                    if (inSelectionMode) toggleSelect(v.uri)
                                    else PlayerActivity.start(context, allUris, allUris.indexOf(v.uri))
                                },
                                onLongClick = { toggleSelect(v.uri) },
                                onActionWatched = {
                                    val next = if (v.isWatched(threshold)) -1 else 1
                                    scope.launch(Dispatchers.IO) { db.videos().setOverride(v.uri, next) }
                                },
                                onActionReset = {
                                    scope.launch(Dispatchers.IO) { db.videos().resetProgressBatch(listOf(v.uri)) }
                                },
                                onActionDelete = {
                                    deleteState.requestDelete(listOf(v))
                                },
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        }
                    }
                }
            }
        }
    }

    if (showTopBar) {
        Scaffold(
            topBar = {
                if (inSelectionMode) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selectedUris = emptySet() }) {
                                Icon(Icons.Default.Close, "선택 취소")
                            }
                        },
                        title = {
                            Text(
                                "${selectedUris.size}개 선택",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        },
                        actions = {
                            val allSelected = here.isNotEmpty() && selectedUris.size == here.size
                            IconButton(onClick = {
                                selectedUris = if (allSelected) emptySet() else here.map { it.uri }.toSet()
                            }) {
                                Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, "모두 선택")
                            }
                            IconButton(onClick = {
                                val list = here.filter { selectedUris.contains(it.uri) }.map { it.uri }
                                if (list.isNotEmpty()) PlayerActivity.start(context, list, 0)
                            }) {
                                Icon(Icons.Default.PlayArrow, "선택 재생", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                deleteState.requestDelete(videos.filter { it.uri in selectedUris })
                            }) {
                                Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.error)
                            }
                            var showBatchMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showBatchMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "작업 더보기")
                                }
                                DropdownMenu(
                                    expanded = showBatchMenu,
                                    onDismissRequest = { showBatchMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("시청 완료로 표시") },
                                        leadingIcon = { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32)) },
                                        onClick = {
                                            val uris = selectedUris.toList()
                                            scope.launch(Dispatchers.IO) { db.videos().setOverrideBatch(uris, 1) }
                                            selectedUris = emptySet()
                                            showBatchMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("미시청으로 표시") },
                                        leadingIcon = { Icon(Icons.Default.RemoveDone, null) },
                                        onClick = {
                                            val uris = selectedUris.toList()
                                            scope.launch(Dispatchers.IO) { db.videos().setOverrideBatch(uris, -1) }
                                            selectedUris = emptySet()
                                            showBatchMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("재생 기록 초기화") },
                                        leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                        onClick = {
                                            val uris = selectedUris.toList()
                                            scope.launch(Dispatchers.IO) { db.videos().resetProgressBatch(uris) }
                                            selectedUris = emptySet()
                                            showBatchMenu = false
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("선택 삭제", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            deleteState.requestDelete(videos.filter { it.uri in selectedUris })
                                            showBatchMenu = false
                                        },
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    TopAppBar(
                        title = { Text(crumbs.joinToString(" / "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (onBack != null) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll(retryMetadata = true) } }) {
                                Icon(Icons.Default.Refresh, "새로고침")
                            }
                            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "설정") }
                            IconButton(onClick = {
                                if (here.isNotEmpty()) selectedUris = setOf(here.first().uri)
                            }) {
                                Icon(Icons.Default.Checklist, "선택 모드")
                            }
                            var showUnregisterDialog by remember { mutableStateOf(false) }
                            IconButton(onClick = { showUnregisterDialog = true }) {
                                Icon(Icons.Default.DeleteOutline, "폴더 등록 해제")
                            }
                            if (showUnregisterDialog) {
                                AlertDialog(
                                    onDismissRequest = { showUnregisterDialog = false },
                                    title = { Text("폴더 등록 해제") },
                                    text = { Text("이 폴더를 라이브러리에서 제외합니다. 실제 파일은 삭제되지 않습니다.") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showUnregisterDialog = false
                                            LibraryWork.scope.launch {
                                                db.folders().byId(folderId)?.let { scanner.unregister(it) }
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    folderDeletedCallback.value?.invoke()
                                                }
                                            }
                                        }) { Text("해제", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showUnregisterDialog = false }) { Text("취소") }
                                    },
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            },
        ) { pad ->
            folderContent(Modifier.padding(pad))
        }
    } else {
        folderContent(Modifier)
    }


}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoRow(
    v: VideoEntity,
    threshold: Double,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onActionWatched: () -> Unit,
    onActionReset: () -> Unit,
    onActionDelete: (() -> Unit)?,
    thumbWidth: Dp = 116.dp,
) {
    val context = LocalContext.current
    LaunchedEffect(v.uri, v.sizeBytes, v.lastModified, v.metadataChecked) {
        VideoMetadata.ensure(context.applicationContext, v)
    }
    val watched = v.isWatched(threshold)
    val inProgress = v.isInProgress(threshold)
    val rowThumbWidth = adaptiveThumbnailWidth(thumbWidth)
    var showMenu by remember { mutableStateOf(false) }

    val canSwipeDelete = !inSelectionMode && onActionDelete != null

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                if (canSwipeDelete) {
                    onActionDelete?.invoke()
                }
                false
            } else {
                false
            }
        },
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canSwipeDelete,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val isEndToStart = direction == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isEndToStart) MaterialTheme.colorScheme.error else Color.Transparent)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (isEndToStart) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (inSelectionMode) "${v.name} 선택 변경" else "${v.name} 재생",
                    onLongClickLabel = "${v.name} 길게 누르기",
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = videoAnnouncement(v)
                    stateDescription = videoPlaybackState(v, threshold)
                    selected = isSelected
                }
                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
        // Thumbnail: green top-end badge = watched, blue top-start badge = long-press selected.
        Thumb(
            video = v,
            modifier = Modifier
                .size(rowThumbWidth, rowThumbWidth * 9f / 16f)
                .clip(RoundedCornerShape(8.dp)),
            isWatched = watched,
            isSelected = isSelected,
        )

        Spacer(Modifier.width(12.dp))

        // Expanded text column — NO 1-line truncation, rich metadata
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Text(
                v.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (inProgress) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(3.dp))

            // Breadcrumbs can wrap; short file metadata remains an atomic chunk.
            if (v.dirPath.isNotEmpty()) {
                Text(v.dirPath, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val sizeStr = fmtSize(v.sizeBytes)
                if (sizeStr.isNotEmpty()) {
                    Text(sizeStr, softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                val dateStr = fmtDate(v.lastModified)
                if (dateStr.isNotEmpty()) {
                    Text(dateStr, softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            MediaDetails(v)

            Spacer(Modifier.height(3.dp))

            // Progress status badge + Playback timestamp info
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    watched -> Badge(containerColor = Color(0xFF2E7D32)) {
                        Text("완료", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    inProgress -> Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(pct(v.fraction), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                    else -> Badge(containerColor = Color(0xFFE65100)) {
                        Text("NEW", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (v.durationSec > 0) {
                    val posStr = fmtTime(v.positionSec)
                    val durStr = fmtTime(v.durationSec)
                    val remainStr = if (inProgress) "(-${fmtTime((v.durationSec - v.positionSec).coerceAtLeast(0.0))})" else ""
                    Text(
                        posStr,
                        softWrap = false,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    Text(
                        "/ $durStr",
                        softWrap = false,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    if (remainStr.isNotEmpty()) {
                        Text(remainStr, softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            if (v.lastPlayedAt > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "최근 시청: " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(v.lastPlayedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray.copy(alpha = 0.8f),
                )
            }

            // Progress bar
            if (v.durationSec > 0) {
                LinearProgressIndicator(
                    progress = { v.fraction.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (watched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Right side: file context menu only (selection is long-press only).
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, "${v.name} 작업 더보기", tint = Color.Gray)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (watched) "미시청으로 표시" else "시청 완료로 표시", modifier = Modifier.clearAndSetSemantics { }) },
                    onClick = { onActionWatched(); showMenu = false },
                    leadingIcon = { Icon(if (watched) Icons.Default.RemoveDone else Icons.Default.CheckCircle, null) },
                    modifier = Modifier.semantics {
                        contentDescription = "${v.name} ${if (watched) "미시청으로 표시" else "시청 완료로 표시"}"
                    },
                )
                DropdownMenuItem(
                    text = { Text("재생 기록 초기화", modifier = Modifier.clearAndSetSemantics { }) },
                    onClick = { onActionReset(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                    modifier = Modifier.semantics { contentDescription = "${v.name} 재생 기록 초기화" },
                )
                if (onActionDelete != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("삭제", color = MaterialTheme.colorScheme.error, modifier = Modifier.clearAndSetSemantics { }) },
                        onClick = { onActionDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.semantics { contentDescription = "${v.name} 삭제" },
                    )
                }
            }
        }
    }
}
}

// ---------------------------------------------------------------- Settings

private enum class SettingsCategory(val label: String) {
    Playback("재생"),
    Library("라이브러리"),
    Advanced("고급·로그"),
}

private fun LazyListScope.categoryItem(
    category: SettingsCategory,
    selectedCategory: SettingsCategory,
    key: String,
    content: @Composable LazyItemScope.() -> Unit,
) {
    if (category == selectedCategory) item(key = key, content = content)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepo(context) }
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(SettingsCategory.Playback) }
    val listState = rememberLazyListState()
    LaunchedEffect(category) { listState.scrollToItem(0) }

    var defaultSpeed by remember { mutableStateOf(1.0) }
    var speedPresets by remember { mutableStateOf<List<Double>>(SettingsRepo.DEFAULT_SPEED_PRESETS) }
    var videoAlignY by remember { mutableStateOf(SettingsRepo.DEFAULT_VIDEO_ALIGN_Y) }
    var threshold by remember { mutableStateOf(0.9) }
    var autoAdvance by remember { mutableStateOf(false) }
    var mpvOptions by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    // P0: gesture & convenience settings
    var tapSeekSec by remember { mutableStateOf(10.0) }
    var fastSpeed by remember { mutableStateOf(2.0) }
    var rememberBright by remember { mutableStateOf(false) }
    var autoSub by remember { mutableStateOf(true) }
    var thumbScale by remember { mutableStateOf("medium") }
    var speedStep by remember { mutableStateOf(SettingsRepo.DEFAULT_SPEED_STEP) }
    var continuePlaylistMode by remember { mutableStateOf(SettingsRepo.DEFAULT_CONTINUE_PLAYLIST) }

    // UI state for inputs & modals
    var newSpeedInput by remember { mutableStateOf("") }
    var speedInputError by remember { mutableStateOf<String?>(null) }
    var showAlignDialog by remember { mutableStateOf(false) }
    var showMpvDialog by remember { mutableStateOf(false) }
    var showBulkPresetsDialog by remember { mutableStateOf(false) }
    var bulkPresetsInput by remember { mutableStateOf("") }
    var bulkPresetsError by remember { mutableStateOf<String?>(null) }
    var customStepInput by remember { mutableStateOf("") }
    var customStepError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        defaultSpeed = settings.defaultSpeed.first()
        speedPresets = settings.speedPresets.first()
        videoAlignY = settings.videoAlignY.first()
        threshold = settings.watchedThreshold.first()
        autoAdvance = settings.autoAdvance.first()
        mpvOptions = settings.mpvOptionsRaw.first()
        tapSeekSec = settings.tapSeekSec.first()
        fastSpeed = settings.fastSpeed.first()
        rememberBright = settings.rememberBrightness.first()
        autoSub = settings.autoSubtitle.first()
        thumbScale = settings.thumbScale.first()
        speedStep = settings.speedStep.first()
        continuePlaylistMode = settings.continuePlaylistMode.first()
        loaded = true
    }
    if (!loaded) return

    fun onAddSpeed() {
        val res = SettingsRepo.validateAndParseSpeedPresets(newSpeedInput)
        if (res.isFailure) {
            speedInputError = res.exceptionOrNull()?.message ?: "0.1 ~ 5.0 사이의 숫자 입력 (예: 1.3)"
        } else {
            val newValues = res.getOrThrow()
            val updated = (speedPresets + newValues).distinct().sorted()
            speedPresets = updated
            newSpeedInput = ""
            speedInputError = null
            scope.launch { settings.setSpeedPresets(updated) }
        }
    }

    AppScaffold(title = "설정", onBack = onBack) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsCategory.entries.forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { category = option },
                    label = { Text(option.label) },
                )
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = listState,
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 1. 재생 속도 프리셋 커스텀 관리
            categoryItem(SettingsCategory.Playback, category, "speed-presets") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("재생 속도 목록 (프리셋)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "플레이어에 노출될 배속 버튼 목록을 추가하거나 삭제할 수 있습니다. (클릭 시 기본 배속으로 지정)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )

                        // Current speed preset chips with delete capability
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            speedPresets.forEach { s ->
                                val isDefault = abs(s - defaultSpeed) < 0.01
                                FilterChip(
                                    selected = isDefault,
                                    onClick = {
                                        defaultSpeed = s
                                        scope.launch { settings.setDefaultSpeed(s) }
                                    },
                                    label = {
                                        Text(
                                            SettingsRepo.formatSpeed(s) + if (isDefault) " (기본)" else "",
                                            fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    trailingIcon = if (speedPresets.size > 1) {
                                        {
                                            IconButton(
                                                onClick = {
                                                    val updated = speedPresets.filterNot { abs(it - s) < 0.001 }
                                                    speedPresets = updated
                                                    scope.launch {
                                                        settings.setSpeedPresets(updated)
                                                        if (abs(defaultSpeed - s) < 0.01) {
                                                            val newDef = updated.firstOrNull() ?: 1.0
                                                            defaultSpeed = newDef
                                                            settings.setDefaultSpeed(newDef)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(18.dp),
                                            ) {
                                                Icon(Icons.Default.Close, "삭제", modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    } else null,
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Add new speed preset input
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = newSpeedInput,
                                onValueChange = {
                                    newSpeedInput = it
                                    speedInputError = null
                                },
                                label = { Text("속도 추가") },
                                placeholder = { Text("예: 0.8 또는 2.5") },
                                singleLine = true,
                                isError = speedInputError != null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onAddSpeed() }),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            Button(
                                onClick = { onAddSpeed() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(56.dp),
                            ) {
                                Text("추가")
                            }
                        }
                        if (speedInputError != null) {
                            Text(speedInputError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                        }

                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    bulkPresetsInput = speedPresets.joinToString(", ") {
                                        SettingsRepo.formatSpeed(it).removeSuffix("x")
                                    }
                                    bulkPresetsError = null
                                    showBulkPresetsDialog = true
                                },
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("일괄 편집", fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                speedPresets = SettingsRepo.DEFAULT_SPEED_PRESETS
                                defaultSpeed = 1.0
                                scope.launch {
                                    settings.setSpeedPresets(SettingsRepo.DEFAULT_SPEED_PRESETS)
                                    settings.setDefaultSpeed(1.0)
                                }
                            }) {
                                Text("기본 프리셋 복원", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 2. 배속 조절 최소 단위 (Step)
            categoryItem(SettingsCategory.Playback, category, "speed-step") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("배속 조절 최소 단위 (Step)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "플레이어에서 배속을 미세 조절할 때 증감할 최소 단위입니다. (0.01 ~ 1.0, 기본값: ${SettingsRepo.formatSpeed(SettingsRepo.DEFAULT_SPEED_STEP)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )

                        Text("현재 단위: ${SettingsRepo.formatSpeed(speedStep)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))

                        val commonSteps = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.25, 0.5)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            commonSteps.forEach { step ->
                                val isSelected = abs(speedStep - step) < 0.001
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        speedStep = step
                                        customStepError = null
                                        scope.launch { settings.setSpeedStep(step) }
                                    },
                                    label = {
                                        Text(
                                            SettingsRepo.formatSpeed(step) + if (step == SettingsRepo.DEFAULT_SPEED_STEP) " (기본)" else "",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = customStepInput,
                                onValueChange = {
                                    customStepInput = it
                                    customStepError = null
                                },
                                label = { Text("단위 직접 입력") },
                                placeholder = { Text("0.01 ~ 1.0 (예: 0.05)") },
                                singleLine = true,
                                isError = customStepError != null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    val res = SettingsRepo.validateSpeedStep(customStepInput)
                                    if (res.isSuccess) {
                                        val v = res.getOrThrow()
                                        speedStep = v
                                        customStepInput = ""
                                        customStepError = null
                                        scope.launch { settings.setSpeedStep(v) }
                                    } else {
                                        customStepError = res.exceptionOrNull()?.message
                                    }
                                }),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            Button(
                                onClick = {
                                    val res = SettingsRepo.validateSpeedStep(customStepInput)
                                    if (res.isSuccess) {
                                        val v = res.getOrThrow()
                                        speedStep = v
                                        customStepInput = ""
                                        customStepError = null
                                        scope.launch { settings.setSpeedStep(v) }
                                    } else {
                                        customStepError = res.exceptionOrNull()?.message
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(56.dp),
                            ) {
                                Text("적용")
                            }
                        }
                        if (customStepError != null) {
                            Text(
                                customStepError!!,
                                color = MaterialTheme.colorScheme.error,
                                 style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            )
                        }

                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                speedStep = SettingsRepo.DEFAULT_SPEED_STEP
                                customStepError = null
                                scope.launch { settings.setSpeedStep(SettingsRepo.DEFAULT_SPEED_STEP) }
                            }) {
                                Text("기본 단위(${SettingsRepo.formatSpeed(SettingsRepo.DEFAULT_SPEED_STEP)})로 복원", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 3. 이어보기 재생 목록 (플레이리스트 옵션)
            categoryItem(SettingsCategory.Library, category, "continue-playlist") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("이어보기 재생 목록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "이어보기 화면에서 영상을 재생할 때 플레이어에 구성될 재생 목록의 기준을 선택합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )

                        ContinuePlaylistMode.entries.forEach { mode ->
                            val isSelected = continuePlaylistMode == mode.value
                            OutlinedCard(
                                onClick = {
                                    continuePlaylistMode = mode.value
                                    scope.launch { settings.setContinuePlaylistMode(mode.value) }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                                ),
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(mode.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                                        Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 목록형 썸네일 크기 (모든 화면 공통)
            categoryItem(SettingsCategory.Library, category, "thumbnails") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ViewList, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("목록형 썸네일 크기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "폴더 화면과 이어보기의 목록형 썸네일 크기에 바로 적용됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )

                        Text("썸네일 크기", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            listOf("small" to "작게", "medium" to "보통 (추천)", "large" to "크게").forEach { (v, label) ->
                                FilterChip(
                                    selected = thumbScale == v,
                                    onClick = {
                                        thumbScale = v
                                        scope.launch { settings.setThumbScale(v) }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            // 2. 영상 화면 세로 정렬 (Natural Language Selector)
            categoryItem(SettingsCategory.Playback, category, "video-alignment") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerticalAlignTop, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("영상 화면 세로 정렬", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "화면 비율이 다른 영상이 재생될 때 화면 내 수직 배치 위치를 지정합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )

                        val currentAlign = VideoAlign.fromValue(videoAlignY)

                        OutlinedCard(
                            onClick = { showAlignDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(currentAlign.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(currentAlign.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ArrowDropDown, "선택")
                            }
                        }
                    }
                }
            }

            // 3. 시청 완료 및 자동 재생
            categoryItem(SettingsCategory.Playback, category, "watch-state-auto-advance") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("시청 상태 및 연속 재생", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(12.dp))
                        Text("시청 완료 판정 기준: ${pct(threshold)}", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = threshold.toFloat(),
                                onValueChange = { threshold = it.toDouble() },
                                onValueChangeFinished = { scope.launch { settings.setThreshold(threshold) } },
                                valueRange = 0.5f..1.0f,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("자동 다음 영상 재생", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "영상이 끝나면 같은 폴더의 다음 영상을 바로 재생합니다.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoAdvance,
                                onCheckedChange = { autoAdvance = it; scope.launch { settings.setAutoAdvance(it) } },
                            )
                        }
                    }
                }
            }

            // P0: 제스처 및 재생 편의 설정
            categoryItem(SettingsCategory.Playback, category, "gestures") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("제스처 및 재생 편의", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(12.dp))
                        Text("더블탭 탐색 시간: ${tapSeekSec.toInt()}초", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            listOf(5.0, 10.0, 15.0, 30.0).forEach { s ->
                                FilterChip(
                                    selected = tapSeekSec == s,
                                    onClick = {
                                        tapSeekSec = s
                                        scope.launch { settings.setTapSeekSec(s) }
                                    },
                                    label = { Text("${s.toInt()}초") },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("롱프레스 쾌속 배속: ${SettingsRepo.formatSpeed(fastSpeed)}", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = fastSpeed.toFloat(),
                                onValueChange = { fastSpeed = ((it * 4).roundToInt() / 4.0).coerceIn(1.5, 4.0) },
                                onValueChangeFinished = { scope.launch { settings.setFastSpeed(fastSpeed) } },
                                valueRange = 1.5f..4.0f,
                                steps = 9,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("밝기 기억하기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "제스처로 조절한 화면 밝기를 다음 재생에도 유지합니다.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = rememberBright,
                                onCheckedChange = { rememberBright = it; scope.launch { settings.setRememberBrightness(it) } },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("외부 자막 자동 로드", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "영상과 같은 이름의 자막 파일(.srt/.vtt/.ass 등)을 자동으로 불러옵니다.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoSub,
                                onCheckedChange = { autoSub = it; scope.launch { settings.setAutoSubtitle(it) } },
                            )
                        }
                    }
                }
            }

            // 4. 고급 MPV 설정 (버튼 클릭 시 모달 다이얼로그로만 표시)
            categoryItem(SettingsCategory.Advanced, category, "mpv-options") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column {
                            Text("고급 MPV 엔진 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (mpvOptions.isBlank()) "기본 설정 사용 중" else "사용자 정의 옵션 적용 중",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { showMpvDialog = true },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("설정 열기")
                        }
                    }
                }
            }

            // 5. 디버그 로그
            categoryItem(SettingsCategory.Advanced, category, "logs") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("디버그 로그", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(AppLog.info(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                        var logText by remember { mutableStateOf<String?>(null) }
                        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { logText = AppLog.tail(200) }) { Text("로그 보기") }
                            OutlinedButton(onClick = {
                                val i = AppLog.shareIntent(context)
                                if (i != null) context.startActivity(Intent.createChooser(i, "로그 공유"))
                            }) { Text("공유") }
                            OutlinedButton(onClick = { AppLog.clear(); logText = null }) { Text("지우기") }
                        }
                        val t = logText
                        if (t != null) {
                            Text(
                                t.ifEmpty { "(로그 없음)" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .padding(top = 8.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Modal dialog for Video Align natural language options
    if (showAlignDialog) {
        val currentAlign = VideoAlign.fromValue(videoAlignY)
        AlertDialog(
            onDismissRequest = { showAlignDialog = false },
            title = { Text("영상 화면 세로 정렬 선택") },
            text = {
                Column(Modifier.selectableGroup()) {
                    VideoAlign.entries.forEach { align ->
                        val selected = align == currentAlign
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        videoAlignY = align.value
                                        scope.launch { settings.setVideoAlignY(align.value) }
                                        showAlignDialog = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    align.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    align.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlignDialog = false }) { Text("닫기") }
            },
        )
    }

    // Modal dialog for advanced MPV config
    if (showMpvDialog) {
        var tempOptions by remember { mutableStateOf(mpvOptions) }
        var optionWarning by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = { showMpvDialog = false },
            title = { Text("고급 MPV 설정 (mpv.conf)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "libmpv에 전달할 옵션을 key=value 형식으로 한 줄씩 입력하세요.\n예: hwdec=auto, profile=fast\n파일 기록·저장(log-file 포함), 영상·음성 출력 선택(ao/vo), 스크립트·외부 설정·네트워크·외부 파일 경로·앱 관리 옵션은 저장할 수 없습니다. 차단된 줄을 직접 삭제한 뒤 저장하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = tempOptions,
                        onValueChange = { tempOptions = it; optionWarning = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 192.dp),
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { Text("# 추가 옵션 입력") },
                    )
                    optionWarning?.let { warning ->
                        Text(
                            warning,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        tempOptions = ""
                        optionWarning = null
                        scope.launch {
                            val result = settings.setMpvOptions("")
                            mpvOptions = result.normalizedText
                        }
                    }) { Text("초기화") }
                    Button(onClick = {
                        val parsed = SettingsRepo.parseMpvOptions(tempOptions)
                        if (parsed.rejected.isNotEmpty()) {
                            optionWarning = parsed.rejectionMessage()
                        } else {
                            scope.launch {
                                val result = settings.setMpvOptions(tempOptions)
                                if (result.rejected.isNotEmpty()) {
                                    optionWarning = result.rejectionMessage()
                                } else {
                                    mpvOptions = result.normalizedText
                                    showMpvDialog = false
                                }
                            }
                        }
                    }) { Text("저장") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMpvDialog = false }) { Text("닫기") }
            },
        )
    }

    if (showBulkPresetsDialog) {
        AlertDialog(
            onDismissRequest = { showBulkPresetsDialog = false },
            title = { Text("배속 프리셋 일괄 편집") },
            text = {
                Column {
                    Text(
                        "쉼표, 공백, 줄바꿈으로 구분하여 여러 배속을 한 번에 입력할 수 있습니다.\n(0.1 ~ 5.0 범위, 소수점 둘째 자리까지)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bulkPresetsInput,
                        onValueChange = {
                            bulkPresetsInput = it
                            bulkPresetsError = null
                        },
                        isError = bulkPresetsError != null,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    if (bulkPresetsError != null) {
                        Text(
                            bulkPresetsError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val res = SettingsRepo.validateAndParseSpeedPresets(bulkPresetsInput)
                    if (res.isSuccess) {
                        val list = res.getOrThrow()
                        speedPresets = list
                        scope.launch {
                            settings.setSpeedPresets(list)
                            if (defaultSpeed !in list) {
                                val newDef = list.first()
                                defaultSpeed = newDef
                                settings.setDefaultSpeed(newDef)
                            }
                        }
                        showBulkPresetsDialog = false
                    } else {
                        bulkPresetsError = res.exceptionOrNull()?.message ?: "유효하지 않은 입력입니다."
                    }
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkPresetsDialog = false }) {
                    Text("취소")
                }
            },
        )
    }
}

// ---------------------------------------------------------------- helpers

@Composable
fun Thumb(video: VideoEntity, modifier: Modifier, isWatched: Boolean = false, isSelected: Boolean = false) {
    val context = LocalContext.current
    var bmp by remember(video.uri, video.sizeBytes, video.lastModified) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.uri, video.sizeBytes, video.lastModified) {
        bmp = Thumbs.get(context, video.uri, video.sizeBytes, video.lastModified)
    }
    Box(modifier.background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(Icons.Default.Movie, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
        if (isSelected) {
            // Dim the thumbnail so selection reads instantly, distinct from watched green.
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
        if (isWatched) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF2E7D32),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
}
}

fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val b = bytes.toDouble()
    return when {
        b >= 1024 * 1024 * 1024 -> "%.1f GB".format(Locale.US, b / (1024 * 1024 * 1024))
        b >= 1024 * 1024 -> "%.1f MB".format(Locale.US, b / (1024 * 1024))
        b >= 1024 -> "%.0f KB".format(Locale.US, b / 1024)
        else -> "$bytes B"
    }
}

fun fmtDate(millis: Long): String {
    if (millis <= 0) return ""
    return SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(millis))
}

private fun videoPlaybackState(video: VideoEntity, threshold: Double): String = when {
    video.isWatched(threshold) -> "시청 완료"
    video.isInProgress(threshold) -> "시청 중 ${pct(video.fraction)}"
    else -> "미시청"
}

/** Keep the preferred size on roomy screens; reserve text space on narrow/large-font layouts. */
@Composable
private fun adaptiveThumbnailWidth(preferred: Dp): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    return if (width < 360 || width / fontScale < 300f) minOf(preferred, 72.dp) else preferred
}

private fun videoResolution(video: VideoEntity): String =
    if ((video.videoWidth ?: 0) > 0 && (video.videoHeight ?: 0) > 0) {
        "${video.videoWidth}×${video.videoHeight}"
    } else "해상도 미확인"

private fun videoSubtitleLabel(video: VideoEntity): String = when (video.hasSubtitles) {
    true -> "자막 있음"
    false -> "자막 없음"
    null -> "자막 미확인"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaDetails(video: VideoEntity) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(videoResolution(video), softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(videoSubtitleLabel(video), softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        if (video.durationSec <= 0) {
            Text("길이 미확인", softWrap = false, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun videoAnnouncement(video: VideoEntity): String = buildList {
    add(video.name)
    if (video.dirPath.isNotEmpty()) add(video.dirPath)
    fmtSize(video.sizeBytes).takeIf { it.isNotEmpty() }?.let { add(it) }
    fmtDate(video.lastModified).takeIf { it.isNotEmpty() }?.let { add(it) }
    add(videoResolution(video))
    add(videoSubtitleLabel(video))
    if (video.durationSec <= 0) add("길이 미확인")
    if (video.durationSec > 0) add("${fmtTime(video.positionSec)} / ${fmtTime(video.durationSec)}")
    if (video.lastPlayedAt > 0) {
        add("최근 시청: " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(video.lastPlayedAt)))
    }
}.joinToString(", ")

/** Zero-pad digit runs so "Ep 2" sorts before "Ep 10". */
fun naturalKey(name: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < name.length) {
        val c = name[i]
        if (c.isDigit()) {
            var j = i
            while (j < name.length && name[j].isDigit()) j++
            sb.append("%08d".format(name.substring(i, j).toLongOrNull() ?: 0))
            i = j
        } else {
            sb.append(c.lowercaseChar())
            i++
        }
    }
    return sb.toString()
}

/**
 * Builds playlist uris and start index for continue-watching playback.
 * ORIGINAL_FOLDER: same folderId AND dirPath, ordered naturalKey.
 * CONTINUE_LIST: continue-watching items preserving displayed order and clicked index.
 */
fun buildContinuePlaylist(
    mode: ContinuePlaylistMode,
    target: VideoEntity,
    continueWatchingList: List<VideoEntity>,
    folderVideos: List<VideoEntity>,
): Pair<List<String>, Int> {
    return when (mode) {
        ContinuePlaylistMode.CONTINUE_LIST -> {
            val uris = continueWatchingList.map { it.uri }
            val idx = uris.indexOf(target.uri).coerceAtLeast(0)
            uris to idx
        }
        ContinuePlaylistMode.ORIGINAL_FOLDER -> {
            val sameFolderAndDir = folderVideos
                .filter { it.folderId == target.folderId && it.dirPath == target.dirPath }
                .map { it to naturalKey(it.name) }
                .sortedBy { it.second }
                .map { it.first }
            val uris = sameFolderAndDir.map { it.uri }.ifEmpty { listOf(target.uri) }
            val idx = uris.indexOf(target.uri).coerceAtLeast(0)
            uris to idx
        }
    }
}

fun pct(f: Double): String = "${(f * 100).roundToInt()}%"

fun fmtTime(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
