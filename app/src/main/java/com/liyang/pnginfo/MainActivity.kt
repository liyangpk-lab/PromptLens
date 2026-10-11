package com.liyang.pnginfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedImageUri(intent)
        setContent {
            AppTheme {
                if (shared != null) {
                    // 从别的 app 分享/打开的单张图片：只看信息，不做文件操作
                    val single = remember {
                        listOf(MediaItem(-1, shared, shared.lastPathSegment?.substringAfterLast('/') ?: "image", 0, 0, -1, "", "", ""))
                    }
                    DetailScreen(single, 0, onBack = { finish() })
                } else {
                    GalleryApp()
                }
            }
        }
    }

    private fun sharedImageUri(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "已复制：$label", Toast.LENGTH_SHORT).show()
}

// ------------------------------------------------------------------ 详情

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    images: List<MediaItem>,
    start: Int,
    onBack: () -> Unit,
    actions: (@Composable RowScope.(MediaItem) -> Unit)? = null,  // 收藏、复制、剪切、删除等
) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val pager = rememberPagerState(initialPage = start) { images.size }
    val cache = remember { mutableStateMapOf<Uri, ImageMeta>() }
    var fullscreen by remember { mutableStateOf<Uri?>(null) }
    val cur = images.getOrNull(pager.currentPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cur?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (cur != null && actions != null) actions(cur)
                    val meta = cur?.let { cache[it.uri] }
                    if (meta != null && !meta.isEmpty) {
                        TextButton(onClick = { copy(ctx, "全部信息", meta.toA1111Text()) }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("全部")
                        }
                    }
                },
            )
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize(), beyondViewportPageCount = 1, key = { images[it].uri.toString() }) { page ->
            val e = images[page]
            val meta = cache[e.uri]
            LaunchedEffect(e.uri) {
                if (cache[e.uri] == null) {
                    cache[e.uri] = withContext(Dispatchers.IO) {
                        runCatching { MetadataReader.read(ctx, e.uri) }
                            .getOrElse { ImageMeta(null, null, null, emptyList(), raw = listOf("错误" to (it.message ?: it.toString()))) }
                    }
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                AsyncImage(
                    model = e.uri, contentDescription = e.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { fullscreen = e.uri },
                )
                Spacer(Modifier.height(10.dp))
                if (meta == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                } else {
                    MetaContent(meta)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    fullscreen?.let { uri ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreen = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullscreen = null }) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MetaContent(m: ImageMeta) {
    val ctx = LocalContext.current
    if (m.source != null) {
        AssistChip(onClick = {}, label = { Text(m.source) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) })
        Spacer(Modifier.height(6.dp))
    }
    if (m.positive == null && m.negative == null && m.extraPrompts.isEmpty() && m.params.size <= 1) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                if (m.raw.isEmpty()) "这张图片没有找到生成信息。\n（可能已被压缩、转存或软件没有写入元数据）"
                else "这张图片里没有提示词和生成参数，只有下面的原始元数据。",
                Modifier.padding(16.dp),
            )
        }
    }
    m.positive?.let { PromptCard("正向提示词 Positive", it, MaterialTheme.colorScheme.primaryContainer) }
    m.negative?.let { PromptCard("负向提示词 Negative", it, MaterialTheme.colorScheme.errorContainer) }
    m.extraPrompts.forEachIndexed { i, t -> PromptCard("提示词 ${i + 1}", t, MaterialTheme.colorScheme.secondaryContainer) }

    if (m.params.isNotEmpty()) {
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("生成参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy(ctx, "生成参数", m.params.joinToString(", ") { "${it.first}: ${it.second}" }) }) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制参数")
                    }
                }
                m.params.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(k, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp))
                        SelectionContainer(Modifier.weight(1f)) { Text(v, style = MaterialTheme.typography.bodyMedium) }
                        IconButton(onClick = { copy(ctx, k, v) }, Modifier.size(40.dp)) {
                            Icon(Icons.Default.ContentCopy, "复制 $k", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (m.raw.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("原始元数据 (${m.raw.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (open) m.raw.forEach { (k, v) ->
                HorizontalDivider()
                Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { copy(ctx, k, v) }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制")
                        }
                    }
                    val shown = if (v.length > 4000) v.take(4000) + "\n…（共 ${v.length} 字符，点复制获取完整内容）" else v
                    SelectionContainer {
                        Text(shown, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptCard(title: String, text: String, color: Color) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { copy(ctx, title, text) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("复制")
                }
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
