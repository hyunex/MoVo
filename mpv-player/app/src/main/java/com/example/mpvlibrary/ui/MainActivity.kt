package com.example.mpvlibrary.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    val scanner = remember { LibraryScanner(context) }
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

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            onSettings = { screen = Screen.Settings },
        )
        is Screen.Folder -> FolderScreen(
            folderId = s.folderId, path = s.path,
            onPath = { screen = Screen.Folder(s.folderId, it) },
            onBack = {
                screen = when {
                    s.path.contains('/') -> Screen.Folder(s.folderId, s.path.substringBeforeLast('/'))
                    s.path.isNotEmpty() -> Screen.Folder(s.folderId, "")
                    else -> Screen.Library
                }
            },
            onSettings = { screen = Screen.Settings },
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scanner = remember { LibraryScanner(context) }
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    var recent by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var threshold by remember { mutableStateOf(0.9) }
    var thumbScale by remember { mutableStateOf("medium") }

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
            LibraryScanner.takePermission(context, uri)
            scope.launch(Dispatchers.IO) {
                db.folders().insert(
                    FolderEntity(
                        treeUri = uri.toString(),
                        displayName = LibraryScanner.displayName(context, uri),
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                scanner.scanAll()
            }
        }
    }

    // 중단 지점이 있는 미시청 영상: 탭하면 PlayerActivity가 start 옵션으로 이어 재생
    val continueWatching = remember(recent, threshold) {
        recent.filter { it.positionSec > 0 && !it.isWatched(threshold) }.take(6)
    }

    val playVideoWithFolderContext: (VideoEntity) -> Unit = { v ->
        scope.launch(Dispatchers.IO) {
            val folderVideos = db.videos().forFolder(v.folderId).sortedBy { naturalKey(it.name) }
            val uris = folderVideos.map { it.uri }.ifEmpty { listOf(v.uri) }
            val idx = uris.indexOf(v.uri).coerceAtLeast(0)
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
                                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll() } }) {
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
                                    onPlay = playVideoWithFolderContext,
                                    onRefresh = { scanner.scanAll() },
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
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll() } }) {
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
                                onPlay = playVideoWithFolderContext,
                                onRefresh = { scanner.scanAll() },
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
                    scope.launch(Dispatchers.IO) {
                        val f = db.folders().byId(target.id)
                        db.folders().delete(target.id)
                        db.videos().deleteForFolder(target.id)
                        f?.let { runCatching { LibraryScanner.releasePermission(context, Uri.parse(it.treeUri)) } }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            if (selection is HomeSelection.Folder &&
                                (selection as HomeSelection.Folder).folderId == target.id
                            ) {
                                selection = HomeSelection.ContinueWatching
                                folderPath = ""
                            }
                        }
                    }
                }) { Text("해제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { unregisterTarget = null }) { Text("취소") }
            },
        )
    }

}

@Composable
fun RecentRow(v: VideoEntity, threshold: Double, onClick: () -> Unit) {
    val watched = v.isWatched(threshold)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(
            uri = v.uri,
            modifier = Modifier
                .size(116.dp, 66.dp)
                .clip(RoundedCornerShape(8.dp)),
            isWatched = watched,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.dirPath.isNotEmpty()) {
                    Text(v.dirPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text(
                    pct(v.fraction) + if (watched) " ✓" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (watched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                if (v.durationSec > 0) {
                    Text(" · " + fmtTime(v.positionSec), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingPane(
    videos: List<VideoEntity>,
    threshold: Double,
    onPlay: (VideoEntity) -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    thumbSize: Dp = 116.dp,
) {
    PullRefreshWrapper(
        modifier = modifier.fillMaxSize(),
        onRefresh = onRefresh,
    ) {
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
                    VideoRow(
                        v = v,
                        threshold = threshold,
                        isSelected = false,
                        inSelectionMode = false,
                        thumbWidth = thumbSize,
                        onClick = { onPlay(v) },
                        onLongClick = { onPlay(v) },
                        onActionWatched = {},
                        onActionReset = {},
                        onActionDelete = {},
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Folder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    folderId: Long,
    path: String,
    onPath: (String) -> Unit,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    showTopBar: Boolean = true,
    onFolderDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scanner = remember { LibraryScanner(context) }
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<FolderEntity?>(null) }
    var videos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var threshold by remember { mutableStateOf(0.9) }
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(true) }
    var unseenOnly by remember { mutableStateOf(false) }
    var thumbScale by remember { mutableStateOf("medium") }

    // Multi-selection state for library file management
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    val inSelectionMode = selectedUris.isNotEmpty()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetUris by remember { mutableStateOf<List<String>>(emptyList()) }
    // 권한 회수 시: 삭제 보류 목록 + 재요청 런처. 권한을 다시 얻으면 삭제 절차를 이어간다.
    var pendingDeleteUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPermissionLostDialog by remember { mutableStateOf(false) }
    var treeWriteLost by remember { mutableStateOf(false) }
    // 권한 재요청 대상 폴더: 피커를 문제 폴더에서 바로 열기 위한 initialUri + 표시명.
    var reauthTarget by remember { mutableStateOf<FolderEntity?>(null) }
    val treePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        AppLog.i("library", "reauth picker result: ${uri != null}")
        if (uri == null) {
            reauthTarget = null
        }
        if (uri != null) {
            LibraryScanner.takePermission(context, uri)
            AppLog.i(
                "library",
                "reauth grant persisted=" +
                    LibraryScanner.hasPersistedPermission(context, uri, write = true),
            )
            scope.launch(Dispatchers.IO) {
                // 재선택한 트리로 폴더 행을 갱신: 권한-폴더 어긋남 방지 + 스캔으로 목록 복구.
                // 같은 폴더를 다시 고르면 treeUri가 동일해 id가 유지되고,
                // 다른 폴더를 고르면 이 폴더 행의 트리만 교체한다(신규 등록 아님).
                val cur = db.folders().byId(folderId)
                if (cur != null && cur.treeUri != uri.toString()) {
                    db.folders().updateTree(
                        folderId, uri.toString(),
                        LibraryScanner.displayName(context, uri),
                    )
                    folder = db.folders().byId(folderId)
                }
                scanner.scanAll()
                val retry = pendingDeleteUris
                pendingDeleteUris = emptyList()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    treeWriteLost = false
                    reauthTarget = null
                    if (retry.isNotEmpty()) {
                        deleteTargetUris = retry
                        showDeleteDialog = true
                    }
                }
            }
        } else {
            pendingDeleteUris = emptyList()
        }
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

    val subDirs = remember(videos, path) {
        val prefix = if (path.isEmpty()) "" else "$path/"
        videos.asSequence()
            .map { it.dirPath }
            .filter { it.startsWith(prefix) && it != path }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }
    val here = remember(videos, path, query, unseenOnly, sortByName) {
        videos.asSequence()
            .filter { it.dirPath == path }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { !unseenOnly || (it.positionSec == 0.0) }
            .sortedWith(if (sortByName) compareBy { naturalKey(it.name) } else compareByDescending { it.lastPlayedAt })
            .toList()
    }
    val allUris = here.map { it.uri }

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
                            pendingDeleteUris = emptyList()
                            reauthTarget = folder
                            showPermissionLostDialog = true
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
                            deleteTargetUris = selectedUris.toList()
                            showDeleteDialog = true
                        }) {
                            Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Search & Filter controls
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("파일명 검색") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "지우기")
                        }
                    },
                )
                IconButton(onClick = { sortByName = !sortByName }) {
                    Icon(if (sortByName) Icons.Default.SortByAlpha else Icons.Default.History, "정렬")
                }
                FilterChip(
                    selected = unseenOnly, onClick = { unseenOnly = !unseenOnly },
                    label = { Text("미시청") },
                    shape = RoundedCornerShape(8.dp),
                )
            }

            // Videos: list only.
            val thumbSize: Dp = when (thumbScale) {
                "small" -> 88.dp
                "large" -> 148.dp
                else -> 116.dp
            }
            PullRefreshWrapper(
                modifier = Modifier.weight(1f),
                onRefresh = { scanner.scanAll() },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (subDirs.isNotEmpty()) {
                        items(subDirs, key = { "d$folderId$it" }) { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPath(if (path.isEmpty()) d else "$path/$d") }
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
                                    deleteTargetUris = listOf(v.uri)
                                    showDeleteDialog = true
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
                                deleteTargetUris = selectedUris.toList()
                                showDeleteDialog = true
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
                                            deleteTargetUris = selectedUris.toList()
                                            showDeleteDialog = true
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
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { scanner.scanAll() } }) {
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
                                            scope.launch(Dispatchers.IO) {
                                                val f = db.folders().byId(folderId)
                                                db.folders().delete(folderId)
                                                db.videos().deleteForFolder(folderId)
                                                f?.let { runCatching { LibraryScanner.releasePermission(context, Uri.parse(it.treeUri)) } }
                                                kotlinx.coroutines.withContext(Dispatchers.Main) { onFolderDeleted() }
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

    // Delete confirmation dialog
    if (showDeleteDialog && deleteTargetUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; deleteTargetUris = emptyList() },
            title = { Text("동영상 삭제") },
            text = {
                Column {
                    Text(
                        "${deleteTargetUris.size}개의 동영상을 라이브러리 및 저장공간에서 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targets = deleteTargetUris
                        showDeleteDialog = false
                        deleteTargetUris = emptyList()
                        // 삭제 전 폴더 트리 쓰기 권한 확인: 시스템 설정에서 회수된 경우
                        // 아래 DocumentsContract 호출이 SecurityException으로 실패하므로,
                        // 먼저 확인하고 재요청 다이얼로그로 안내한다.
                        val treeUri = folder?.let { runCatching { Uri.parse(it.treeUri) }.getOrNull() }
                        if (treeUri != null && !LibraryScanner.hasPersistedPermission(context, treeUri, write = true)) {
                            pendingDeleteUris = targets
                            reauthTarget = folder
                            showPermissionLostDialog = true
                            return@Button
                        }
                        scope.launch(Dispatchers.IO) {
                            var deletedCount = 0
                            var failedCount = 0
                            val failedUris = mutableListOf<String>()

                            // SAF 전용 삭제: 직접 파일 경로 삭제(File.delete/MediaStore)는
                            // MANAGE_EXTERNAL_STORAGE 없이는 타 앱 영역에서 오동작하므로 시도하지 않는다.
                            // 순서: DocumentsContract -> 단일 DocumentFile -> 등록 트리에서 탐색 삭제.
                            targets.forEach { u ->
                                val parsed = Uri.parse(u)
                                folder?.let { f ->
                                    LibraryScanner.takePermission(context, Uri.parse(f.treeUri))
                                }
                                var success = false
                                if (!success) {
                                    val contractRes = runCatching {
                                        android.provider.DocumentsContract.deleteDocument(context.contentResolver, parsed)
                                    }
                                    if (contractRes.isSuccess && contractRes.getOrNull() == true) {
                                        success = true
                                    }
                                }
                                if (!success) {
                                    val docRes = runCatching {
                                        DocumentFile.fromSingleUri(context, parsed)?.delete() == true
                                    }
                                    if (docRes.isSuccess && docRes.getOrNull() == true) {
                                        success = true
                                    }
                                }
                                if (!success) {
                                    val treeRes = runCatching {
                                        folder?.let { f ->
                                            val root = DocumentFile.fromTreeUri(context, Uri.parse(f.treeUri))
                                            val entity = db.videos().byUri(u)
                                            if (entity != null && root != null) {
                                                var dir: DocumentFile? = root
                                                if (entity.dirPath.isNotEmpty()) {
                                                    for (seg in entity.dirPath.split('/')) {
                                                        if (seg.isEmpty() || seg == "." || seg == "..") continue
                                                        dir = dir?.findFile(seg) ?: break
                                                    }
                                                }
                                                dir?.findFile(entity.name)?.delete() == true
                                            } else false
                                        } == true
                                    }
                                    if (treeRes.isSuccess && treeRes.getOrNull() == true) {
                                        success = true
                                    }
                                }
                                if (success) {
                                    deletedCount++
                                    AppLog.i("library", "file deleted via SAF")
                                } else {
                                    failedCount++
                                    failedUris.add(u)
                                    AppLog.w("library", "file SAF delete failed")
                                }
                            }

                            val successfullyDeleted = targets - failedUris.toSet()
                            if (successfullyDeleted.isNotEmpty()) {
                                db.videos().deleteByUris(successfullyDeleted)
                                selectedUris = selectedUris - successfullyDeleted.toSet()
                            }

                            if (failedCount > 0) {
                                // 삭제 도중 권한 회수를 확인: 트리 권한이 없으면 보류 목록을 들고 재요청으로 유도.
                                val lost = folder?.let { f ->
                                    runCatching { Uri.parse(f.treeUri) }.getOrNull()?.let { t ->
                                        !LibraryScanner.hasPersistedPermission(context, t, write = true)
                                    } ?: false
                                } ?: false
                                if (lost) {
                                    val lostFolder = folder
                                    pendingDeleteUris = failedUris.toList()
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        reauthTarget = lostFolder
                                        showPermissionLostDialog = true
                                    }
                                } else {
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "${deletedCount}개 삭제 완료 (${failedCount}개 실패: 폴더 접근 권한을 확인해 주세요)",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "${deletedCount}개의 동영상이 삭제되었습니다.",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; deleteTargetUris = emptyList() }) {
                    Text("취소")
                }
            },
        )
    }

    // 폴더 접근 권한이 회수된 경우: 문제 폴더를 바로 보여주고 권한을 다시 얻는다.
    if (showPermissionLostDialog) {
        val target = reauthTarget
        val targetName = target?.displayName ?: folder?.displayName
        AlertDialog(
            onDismissRequest = { showPermissionLostDialog = false; pendingDeleteUris = emptyList(); reauthTarget = null },
            icon = { Icon(Icons.Default.FolderShared, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("폴더 접근 권한 필요") },
            text = {
                Text(
                    (if (targetName != null) "\"${targetName}\" 폴더의 접근 권한이 회수되어 파일을 삭제할 수 없습니다.\n\n"
                    else "등록된 폴더의 접근 권한이 회수되어 파일을 삭제할 수 없습니다.\n\n") +
                        "[폴더 다시 선택]을 누르면 문제가 있는 폴더를 바로 보여주니, " +
                        "해당 폴더에서 [이 폴더 사용]을 눌러 권한을 다시 허용해 주세요. " +
                        "실제 파일은 건드리지 않고 권한만 다시 얻은 뒤 삭제 절차를 이어갑니다."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionLostDialog = false
                    // 문제 폴더를 initialUri로 넘겨 피커가 그 폴더를 바로 표시하게 한다.
                    val initial = target?.let { runCatching { Uri.parse(it.treeUri) }.getOrNull() }
                    treePermissionLauncher.launch(initial)
                }) {
                    Text("폴더 다시 선택")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionLostDialog = false; pendingDeleteUris = emptyList(); reauthTarget = null }) {
                    Text("취소")
                }
            },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
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
    onActionDelete: () -> Unit,
    thumbWidth: Dp = 116.dp,
) {
    val watched = v.isWatched(threshold)
    val inProgress = v.isInProgress(threshold)
    var showMenu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail: green top-end badge = watched, blue top-start badge = long-press selected.
        Thumb(
            uri = v.uri,
            modifier = Modifier
                .size(thumbWidth, (thumbWidth.value * 9f / 16f).dp)
                .clip(RoundedCornerShape(8.dp)),
            isWatched = watched,
            isSelected = isSelected,
        )

        Spacer(Modifier.width(12.dp))

        // Expanded text column — NO 1-line truncation, rich metadata
        Column(Modifier.weight(1f)) {
            Text(
                v.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (inProgress) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(3.dp))

            // Subfolder breadcrumb + File size + Last modified date
            val metaList = mutableListOf<String>()
            if (v.dirPath.isNotEmpty()) metaList.add(v.dirPath)
            val sizeStr = fmtSize(v.sizeBytes)
            if (sizeStr.isNotEmpty()) metaList.add(sizeStr)
            val dateStr = fmtDate(v.lastModified)
            if (dateStr.isNotEmpty()) metaList.add(dateStr)

            if (metaList.isNotEmpty()) {
                Text(
                    metaList.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }

            Spacer(Modifier.height(3.dp))

            // Progress status badge + Playback timestamp info
            Row(
                verticalAlignment = Alignment.CenterVertically,
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
                    val remainStr = if (inProgress) " (-${fmtTime((v.durationSec - v.positionSec).coerceAtLeast(0.0))})" else ""
                    Text(
                        "$posStr / $durStr$remainStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
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
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, "더보기", tint = Color.Gray)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (watched) "미시청으로 표시" else "시청 완료로 표시") },
                    onClick = { onActionWatched(); showMenu = false },
                    leadingIcon = { Icon(if (watched) Icons.Default.RemoveDone else Icons.Default.CheckCircle, null) },
                )
                DropdownMenuItem(
                    text = { Text("재생 기록 초기화") },
                    onClick = { onActionReset(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                    onClick = { onActionDelete(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepo(context) }
    val scope = rememberCoroutineScope()

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

    // UI state for inputs & modals
    var newSpeedInput by remember { mutableStateOf("") }
    var speedInputError by remember { mutableStateOf<String?>(null) }
    var showAlignDialog by remember { mutableStateOf(false) }
    var showMpvDialog by remember { mutableStateOf(false) }

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
        loaded = true
    }
    if (!loaded) return

    fun onAddSpeed() {
        val parsed = newSpeedInput.trim().toDoubleOrNull()
        if (parsed == null || parsed < 0.1 || parsed > 5.0) {
            speedInputError = "0.1 ~ 5.0 사이의 숫자 입력 (예: 1.3)"
        } else {
            val rounded = (parsed * 100.0).toInt() / 100.0
            val updated = (speedPresets + rounded).distinct().sorted()
            speedPresets = updated
            newSpeedInput = ""
            speedInputError = null
            scope.launch { settings.setSpeedPresets(updated) }
        }
    }

    AppScaffold(title = "설정", onBack = onBack) {
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 1. 재생 속도 프리셋 커스텀 관리
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("재생 속도 목록 (프리셋)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "플레이어에 노출될 배속 버튼 목록을 추가하거나 삭제할 수 있습니다. (클릭 시 기본 배속으로 지정)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
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
                                            (if (s % 1.0 == 0.0) "${s.toInt()}x" else "${s}x") + if (isDefault) " (기본)" else "",
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

                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
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

            // 목록형 썸네일 크기 (모든 화면 공통)
            item {
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
                            color = Color.Gray,
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
            item {
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
                            color = Color.Gray,
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
                                    Text(currentAlign.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Icon(Icons.Default.ArrowDropDown, "선택")
                            }
                        }
                    }
                }
            }

            // 3. 시청 완료 및 자동 재생
            item {
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
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("제스처 및 재생 편의", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(12.dp))
                        Text("더블탭 탐색 시간: ${tapSeekSec.toInt()}초", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
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
                        Text("롱프레스 쾌속 배속: ${fastSpeed}x", style = MaterialTheme.typography.bodyMedium)
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
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("고급 MPV 엔진 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (mpvOptions.isBlank()) "기본 설정 사용 중" else "사용자 정의 옵션 적용 중",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("디버그 로그", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(AppLog.info(), style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                        var logText by remember { mutableStateOf<String?>(null) }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        AlertDialog(
            onDismissRequest = { showMpvDialog = false },
            title = { Text("고급 MPV 설정 (mpv.conf)") },
            text = {
                Column {
                    Text(
                        "libmpv에 전달할 옵션을 key=value 형식으로 한 줄씩 입력하세요.\n예: hwdec=auto, profile=fast\n보안상 config·script·네트워크·저장 경로 옵션은 적용되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = tempOptions,
                        onValueChange = { tempOptions = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp),
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { Text("# 추가 옵션 입력") },
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        tempOptions = ""
                        mpvOptions = ""
                        scope.launch { settings.setMpvOptions("") }
                    }) { Text("초기화") }
                    Button(onClick = {
                        mpvOptions = tempOptions
                        scope.launch { settings.setMpvOptions(tempOptions) }
                        showMpvDialog = false
                    }) { Text("저장") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMpvDialog = false }) { Text("닫기") }
            },
        )
    }
}

// ---------------------------------------------------------------- helpers

@Composable
fun Thumb(uri: String, modifier: Modifier, isWatched: Boolean = false, isSelected: Boolean = false) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bmp = Thumbs.get(context, uri) }
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
                    Icon(Icons.Default.Check, "선택됨", tint = Color.White, modifier = Modifier.size(13.dp))
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
                    Icon(Icons.Default.Check, "완료", tint = Color.White, modifier = Modifier.size(13.dp))
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

fun pct(f: Double): String = "${(f * 100).roundToInt()}%"

fun fmtTime(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
