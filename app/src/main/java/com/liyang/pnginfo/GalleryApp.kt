package com.liyang.pnginfo

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ 权限

private enum class Access { NONE, PARTIAL, FULL }

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun access(ctx: Context): Access = when {
    Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
    Build.VERSION.SDK_INT < 33 && granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
    Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
    else -> Access.NONE
}

private val PERMISSIONS = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

// ------------------------------------------------------------------ App

private const val TAB_RECENT = 0
private const val TAB_ALBUMS = 1
private const val TAB_FAVORITES = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    val clip = remember { Clip() }

    // 权限（回到 App 时重新检查，用户可能在系统设置里改过）
    var acc by remember { mutableStateOf(access(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { acc = access(ctx) }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) acc = access(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (acc == Access.NONE) permLauncher.launch(PERMISSIONS) }
    if (acc == Access.NONE) {
        PermissionScreen(onGrant = { permLauncher.launch(PERMISSIONS) })
        return
    }

    // 图库数据：图库有变化（新图、删除、移动）自动刷新
    var refresh by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<MediaItem>?>(null) }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val bump = Runnable { refresh++ }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(bump); handler.postDelayed(bump, 400) }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer); handler.removeCallbacks(bump) }
    }
    LaunchedEffect(refresh, acc) {
        val result = withContext(Dispatchers.IO) { runCatching { MediaRepo.queryAll(ctx) } }
        val list = result.getOrDefault(emptyList())
        all = list
        // 只在完整授权且读取成功时清理收藏：部分授权看不到的图、读取失败，都不代表图被删了
        if (acc == Access.FULL && result.isSuccess && list.isNotEmpty()) store.pruneFavorites(list.mapTo(HashSet()) { it.id })
    }

    var tab by rememberSaveable { mutableIntStateOf(TAB_RECENT) }
    var openAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var trashRefresh by remember { mutableIntStateOf(0) }

    // 系统确认框（移动、回收站）：确认后再执行 after
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val next = afterGrant
        afterGrant = null
        if (r.resultCode == Activity.RESULT_OK) next?.invoke() else toast(ctx, "已取消")
    }
    fun askThen(pi: PendingIntent, after: () -> Unit) {
        afterGrant = after
        senderLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    val items = all.orEmpty()
    val albums = remember(items) { MediaRepo.albums(items) }
    val openAlbum = albums.firstOrNull { it.bucketId == openAlbumId }

    // ---------------- 操作
    fun favorite(list: List<MediaItem>) {
        val added = store.toggleFavorites(list.map { it.id })
        toast(ctx, if (added) "已加入收藏（${list.size}）" else "已取消收藏（${list.size}）")
    }
    fun copyOrCut(list: List<MediaItem>, mode: ClipMode) {
        clip.set(list, mode)
        toast(ctx, "${if (mode == ClipMode.COPY) "已复制" else "已剪切"} ${list.size} 张，到「相册」打开目标文件夹点粘贴")
    }
    fun delete(list: List<MediaItem>, after: () -> Unit = {}) =
        askThen(MediaRepo.trashRequest(ctx, list, trash = true)) {
            toast(ctx, "已移到回收站（${list.size}），30 天内可在回收站恢复")
            after()
        }
    fun restore(list: List<MediaItem>) =
        askThen(MediaRepo.trashRequest(ctx, list, trash = false)) { toast(ctx, "已恢复 ${list.size} 张"); trashRefresh++ }
    fun deleteForever(list: List<MediaItem>) =
        askThen(MediaRepo.deleteRequest(ctx, list)) { toast(ctx, "已永久删除 ${list.size} 张"); trashRefresh++ }
    fun paste(target: Album) {
        val src = clip.items
        if (src.isEmpty()) return
        if (clip.mode == ClipMode.COPY) {
            scope.launch {
                busy = "正在复制 ${src.size} 张…"
                val failed = withContext(Dispatchers.IO) { src.mapNotNull { runCatching { MediaRepo.copyInto(ctx, it, target.relPath) }.exceptionOrNull()?.let { e -> it.name to e } } }
                busy = null
                toast(ctx, if (failed.isEmpty()) "已粘贴 ${src.size} 张到「${target.name}」" else "粘贴失败 ${failed.size} 张：${failed.first().second.message}")
            }
        } else {
            val moving = src.filter { it.relPath != target.relPath }
            if (moving.isEmpty()) { toast(ctx, "已经在这个文件夹里"); clip.clear(); return }
            askThen(MediaRepo.writeRequest(ctx, moving)) {
                scope.launch {
                    busy = "正在移动 ${moving.size} 张…"
                    val failed = withContext(Dispatchers.IO) { moving.mapNotNull { runCatching { MediaRepo.moveInto(ctx, it, target.relPath) }.exceptionOrNull()?.let { e -> it.name to e } } }
                    busy = null
                    clip.clear()
                    toast(ctx, if (failed.isEmpty()) "已移动 ${moving.size} 张到「${target.name}」" else "移动失败 ${failed.size} 张：${failed.first().second.message}")
                }
            }
        }
    }

    // ---------------- 详情页
    detail?.let { (list, start) ->
        val live = list.map { old -> items.firstOrNull { it.id == old.id } ?: old }
        DetailScreen(live, start, onBack = { detail = null }) { cur ->
            val fav = store.isFavorite(cur.id)
            IconButton(onClick = { favorite(listOf(cur)) }) {
                Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, if (fav) "取消收藏" else "收藏",
                    tint = if (fav) Color(0xFFFFC107) else LocalContentColor.current)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; copyOrCut(listOf(cur), ClipMode.COPY) })
                    DropdownMenuItem(text = { Text("剪切") }, leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                        onClick = { menu = false; copyOrCut(listOf(cur), ClipMode.CUT) })
                    DropdownMenuItem(text = { Text("删除（移到回收站）") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; delete(listOf(cur)) { detail = null } })
                }
            }
        }
        return
    }

    // 当前画面显示的图片（按设定排序）
    val shown: List<MediaItem> = when {
        tab == TAB_ALBUMS && showTrash -> emptyList()
        tab == TAB_ALBUMS && openAlbum != null -> items.filter { it.bucketId == openAlbum.bucketId }
        tab == TAB_ALBUMS -> emptyList()
        tab == TAB_FAVORITES -> items.filter { it.id in store.favorites }
        else -> items
    }.sortedBy(store.sortKey, store.sortAsc)
    val selecting = selected.isNotEmpty()
    val selectedItems = shown.filter { it.id in selected }

    LaunchedEffect(tab, openAlbumId, showTrash) { selected = emptySet() }
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && tab == TAB_ALBUMS && (openAlbumId != null || showTrash)) { openAlbumId = null; showTrash = false }

    val onItemClick: (MediaItem) -> Unit = { m ->
        if (selecting) selected = if (m.id in selected) selected - m.id else selected + m.id
        else detail = shown to shown.indexOf(m).coerceAtLeast(0)
    }
    val onItemLong: (MediaItem) -> Unit = { m -> selected = selected + m.id }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("已选 ${selected.size}") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                    actions = {
                        TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.mapTo(HashSet()) { it.id } }) {
                            Text(if (selected.size == shown.size) "全不选" else "全选")
                        }
                    },
                )
            } else {
                val title = when {
                    tab == TAB_RECENT -> "最近"
                    tab == TAB_FAVORITES -> "收藏"
                    showTrash -> "回收站"
                    openAlbum != null -> openAlbum.name
                    else -> "相册"
                }
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (tab == TAB_ALBUMS && (openAlbum != null || showTrash))
                            IconButton(onClick = { openAlbumId = null; showTrash = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    },
                    actions = {
                        if (tab == TAB_ALBUMS && openAlbum != null && clip.items.isNotEmpty()) {
                            TextButton(onClick = { paste(openAlbum) }) {
                                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("粘贴 ${clip.items.size}")
                            }
                        }
                        if (tab == TAB_ALBUMS && openAlbum != null) {
                            val pinnedNow = openAlbum.bucketId in store.pinned
                            IconButton(onClick = { store.togglePin(openAlbum.bucketId) }) {
                                Icon(if (pinnedNow) Icons.Default.PushPin else Icons.Outlined.PushPin, if (pinnedNow) "取消钉选" else "钉选")
                            }
                        }
                        if (tab == TAB_ALBUMS && openAlbum == null && !showTrash) {
                            IconButton(onClick = { showTrash = true }) { Icon(Icons.Default.DeleteOutline, "回收站") }
                        }
                        if (!(tab == TAB_ALBUMS && openAlbum == null)) SortMenu(store)
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    allFavorite = selectedItems.isNotEmpty() && selectedItems.all { store.isFavorite(it.id) },
                    onFavorite = { favorite(selectedItems) },
                    onCopy = { copyOrCut(selectedItems, ClipMode.COPY); selected = emptySet() },
                    onCut = { copyOrCut(selectedItems, ClipMode.CUT); selected = emptySet() },
                    onDelete = { delete(selectedItems) { selected = emptySet() } },
                )
            } else {
                NavigationBar {
                    listOf(
                        Triple(TAB_RECENT, "最近", Icons.Default.PhotoLibrary),
                        Triple(TAB_ALBUMS, "相册", Icons.Default.Collections),
                        Triple(TAB_FAVORITES, "收藏", Icons.Default.Star),
                    ).forEach { (t, label, icon) ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                if (tab == t && t == TAB_ALBUMS) { openAlbumId = null; showTrash = false }
                                tab = t
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium) }
            if (acc == Access.PARTIAL) PartialAccessBanner { permLauncher.launch(PERMISSIONS) }
            if (clip.items.isNotEmpty() && !(tab == TAB_ALBUMS && openAlbum != null)) ClipBanner(clip)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    all == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    tab == TAB_ALBUMS && showTrash -> TrashScreen(trashRefresh, onRestore = ::restore, onDeleteForever = ::deleteForever)
                    tab == TAB_ALBUMS && openAlbum == null -> AlbumsScreen(albums, store, onOpen = { openAlbumId = it.bucketId })
                    shown.isEmpty() -> Text(
                        if (tab == TAB_FAVORITES) "还没有收藏的图片\n长按图片选择后点「收藏」，或在大图页点星标" else "没有图片",
                        Modifier.align(Alignment.Center).padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> MediaGrid(
                        shown, store, selected,
                        dateHeaders = store.sortKey == SortKey.DATE,
                        onClick = onItemClick, onLongClick = onItemLong,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("需要访问手机里的照片", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("用来显示最近的图片、相册和收藏，并读取 PNG 里的生成信息。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) { Text("允许访问照片") }
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("去系统设置开启") }
        }
    }
}

@Composable
private fun PartialAccessBanner(onFull: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("目前只允许访问部分照片", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onFull) { Text("允许全部") }
        }
    }
}

@Composable
private fun ClipBanner(clip: Clip) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (clip.mode == ClipMode.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("${if (clip.mode == ClipMode.COPY) "已复制" else "已剪切"} ${clip.items.size} 张：到「相册」打开目标文件夹，点右上角粘贴",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clip.clear() }) { Text("清除") }
        }
    }
}

@Composable
private fun SortMenu(store: Store) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text("排序（再点一次切换正反序）", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SortKey.entries.forEach { k ->
                val on = store.sortKey == k
                val dir = when (k) {
                    SortKey.DATE -> if (store.sortAsc) "旧 → 新" else "新 → 旧"
                    SortKey.NAME -> if (store.sortAsc) "A → Z" else "Z → A"
                    SortKey.SIZE -> if (store.sortAsc) "小 → 大" else "大 → 小"
                }
                DropdownMenuItem(
                    text = { Text(if (on) "${k.label}　$dir" else k.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = {
                        if (on) Icon(if (store.sortAsc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { store.chooseSort(k) },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(allFavorite: Boolean, onFavorite: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit) {
    BottomAppBar {
        @Composable
        fun RowScope.Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
            Column(
                Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
        Action(if (allFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, if (allFavorite) "取消收藏" else "收藏", onFavorite)
        Action(Icons.Default.ContentCopy, "复制", onCopy)
        Action(Icons.Default.ContentCut, "剪切", onCut)
        Action(Icons.Default.Delete, "删除", onDelete)
    }
}

private sealed interface GridRow {
    data class Header(val text: String, val key: String) : GridRow
    data class Cell(val item: MediaItem) : GridRow
}

private val ZONE: ZoneId = ZoneId.systemDefault()
private val FMT_SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)
private val FMT_OTHER_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> if (day.year == today.year) day.format(FMT_SAME_YEAR) else day.format(FMT_OTHER_YEAR)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    store: Store,
    selected: Set<Long>,
    dateHeaders: Boolean,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    val ctx = LocalContext.current
    val rows = remember(items, dateHeaders) {
        if (!dateHeaders) items.map { GridRow.Cell(it) }
        else {
            val today = LocalDate.now(ZONE)
            val out = ArrayList<GridRow>(items.size + 64)
            var last: LocalDate? = null
            for (m in items) {
                val day = Instant.ofEpochMilli(m.time).atZone(ZONE).toLocalDate()
                if (day != last) { out += GridRow.Header(dayLabel(day, today), "h$day"); last = day }
                out += GridRow.Cell(m)
            }
            out
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            rows,
            key = { r -> if (r is GridRow.Header) r.key else (r as GridRow.Cell).item.id },
            span = { r -> if (r is GridRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { r ->
            when (r) {
                is GridRow.Header -> Text(r.text, Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                is GridRow.Cell -> {
                    val m = r.item
                    val sel = m.id in selected
                    Box(
                        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(onClick = { onClick(m) }, onLongClick = { onLongClick(m) })
                    ) {
                        AsyncImage(model = m.uri, contentDescription = m.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().then(if (sel) Modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)) else Modifier))
                        if (store.isFavorite(m.id)) Icon(Icons.Default.Star, "收藏", Modifier.align(Alignment.BottomStart).padding(3.dp).size(16.dp),
                            tint = Color(0xFFFFC107))
                        when (store.sortKey) {
                            SortKey.NAME -> CornerLabel(m.name)
                            SortKey.SIZE -> CornerLabel(Formatter.formatShortFileSize(ctx, m.size))
                            SortKey.DATE -> {}
                        }
                        if (selected.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp).clip(CircleShape)
                                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                                    .border(1.5.dp, Color.White, CircleShape)
                            ) { if (sel) Icon(Icons.Default.Check, null, Modifier.padding(2.dp), tint = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CornerLabel(text: String) {
    Text(
        text, Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 3.dp),
        color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AlbumsScreen(albums: List<Album>, store: Store, onOpen: (Album) -> Unit) {
    val pinned = albums.filter { it.bucketId in store.pinned }
    val others = albums.filter { it.bucketId !in store.pinned }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (pinned.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("已钉选") }
            items(pinned, key = { "p${it.bucketId}" }) { AlbumCard(it, true, store, onOpen) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("全部文件夹") }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("点文件夹右上角的图钉，把常用文件夹钉在最上面", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(others, key = { "a${it.bucketId}" }) { AlbumCard(it, false, store, onOpen) }
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

@Composable
private fun AlbumCard(a: Album, pinned: Boolean, store: Store, onOpen: (Album) -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(a) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = a.cover, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // 不用 IconButton：它的最小触控尺寸会把圆形背景撑大、被卡片圆角切掉
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)).clickable { store.togglePin(a.bucketId) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin, if (pinned) "取消钉选" else "钉选",
                    tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        Text(a.name, Modifier.padding(start = 4.dp, top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text("${a.count} 张", Modifier.padding(start = 4.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashScreen(refreshKey: Int, onRestore: (List<MediaItem>) -> Unit, onDeleteForever: (List<MediaItem>) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var sel by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(refreshKey) {
        items = withContext(Dispatchers.IO) { runCatching { MediaRepo.queryAll(ctx, trashed = true) }.getOrDefault(emptyList()) }
            .sortedByDescending { it.time }
        sel = emptySet()
    }
    val list = items
    // 永久删除前先在 App 里确认一次（之后系统还会再弹一次确认框）
    var confirm by remember { mutableStateOf<List<MediaItem>?>(null) }
    confirm?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text(if (targets.size == list?.size) "清空回收站？" else "永久删除 ${targets.size} 张？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDeleteForever(targets) }) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("删除的图片在这里保留 30 天，之后系统自动清除。长按选择后可以恢复或永久删除。",
                Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!list.isNullOrEmpty() && sel.isEmpty()) {
                TextButton(onClick = { confirm = list }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (sel.isNotEmpty() && list != null) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${sel.size}", Modifier.weight(1f))
                TextButton(onClick = { sel = if (sel.size == list.size) emptySet() else list.mapTo(HashSet()) { it.id } }) {
                    Text(if (sel.size == list.size) "全不选" else "全选")
                }
                OutlinedButton(onClick = { confirm = list.filter { it.id in sel } }) {
                    Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp))
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { onRestore(list.filter { it.id in sel }) }) {
                    Icon(Icons.Default.RestoreFromTrash, null); Spacer(Modifier.width(4.dp)); Text("恢复")
                }
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> Box(Modifier.fillMaxSize()) { Text("回收站是空的", Modifier.align(Alignment.Center)) }
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.id }) { m ->
                    val on = m.id in sel
                    Box(Modifier.aspectRatio(1f).combinedClickable(
                        onClick = { if (sel.isNotEmpty()) sel = if (on) sel - m.id else sel + m.id },
                        onLongClick = { sel = sel + m.id },
                    )) {
                        AsyncImage(model = m.uri, contentDescription = m.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().then(if (on) Modifier.padding(10.dp) else Modifier))
                        if (on) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(4.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
