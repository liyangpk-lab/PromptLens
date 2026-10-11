package com.liyang.pnginfo

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 8f

/**
 * 全屏看图：左右滑动切换图片；双指缩放；放大后拖动查看；
 * 双击在「完整显示」和「铺满屏幕（适应宽或高）」之间切换；单击关闭。
 * onClose 返回关闭时停留的那一张。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomViewer(images: List<MediaItem>, start: Int, onClose: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { images.size }
    var zoomed by remember { mutableStateOf(false) }   // 放大时禁止左右翻页，单指拖动改为移动图片
    Dialog(
        onDismissRequest = { onClose(pager.currentPage) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1,
                key = { images[it].uri.toString() },
            ) { page ->
                ZoomableImage(
                    images[page],
                    active = page == pager.currentPage,
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    onTap = { onClose(pager.currentPage) },
                )
            }
            IconButton(onClick = { onClose(pager.currentPage) }, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White)
            }
            if (images.size > 1) {
                Text("${pager.currentPage + 1} / ${images.size}", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: MediaItem, active: Boolean, onZoomChange: (Boolean) -> Unit, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    var box by remember { mutableStateOf(IntSize.Zero) }
    var imgRatio by remember { mutableFloatStateOf(0f) }   // 宽 / 高
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 翻到别的图时，这张恢复原大小
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale) { if (active) onZoomChange(scale > 1.01f) }

    // scale = 1 时图片完整显示（ContentScale.Fit）的尺寸
    fun fitSize(): Size {
        val bw = box.width.toFloat(); val bh = box.height.toFloat()
        if (imgRatio <= 0f || bw <= 0f || bh <= 0f) return Size(bw, bh)
        return if (bw / bh > imgRatio) Size(bh * imgRatio, bh) else Size(bw, bw / imgRatio)
    }
    /** 铺满屏幕需要的倍数：横图适应高度、竖图适应宽度；比例和屏幕接近时改为放大 2.5 倍。 */
    fun fillScale(): Float {
        val f = fitSize()
        if (f.width <= 0f || f.height <= 0f) return 2.5f
        val s = max(box.width / f.width, box.height / f.height)
        return if (s < 1.2f) 2.5f else min(s, MAX_SCALE)
    }
    fun clamp(o: Offset, s: Float): Offset {
        val f = fitSize()
        val mx = max(0f, (f.width * s - box.width) / 2f)
        val my = max(0f, (f.height * s - box.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun animateTo(targetScale: Float, targetOffset: Offset) {
        val s0 = scale; val o0 = offset
        scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                scale = s0 + (targetScale - s0) * t
                offset = Offset(o0.x + (targetOffset.x - o0.x) * t, o0.y + (targetOffset.y - o0.y) * t)
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(item.uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1.01f) animateTo(1f, Offset.Zero)
                        else {
                            // 以双击的位置为中心放大
                            val target = fillScale()
                            val q = p - Offset(box.width / 2f, box.height / 2f)
                            animateTo(target, clamp(q * (1f - target), target))
                        }
                    },
                )
            }
            .pointerInput(item.uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // 只有双指缩放、或已经放大时才接管；原大小单指滑动交给翻页
                        if (pressed > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            if (c != Offset.Unspecified) {
                                // 保持双指中心下的那一点不动
                                val q = c - Offset(box.width / 2f, box.height / 2f)
                                val ratio = newScale / scale
                                offset = clamp(q - (q - offset) * ratio + pan, newScale)
                            }
                            scale = newScale
                            if (scale <= 1.01f) offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // 松手时：几乎没放大就回到原位
                    if (scale < 1.03f && (scale != 1f || offset != Offset.Zero)) animateTo(1f, Offset.Zero)
                }
            },
    ) {
        val ctx = LocalContext.current
        // 放大要用高分辨率：按原图加载，最长边不超过 4096（避免超大照片占满内存）
        val request = remember(item.uri) { ImageRequest.Builder(ctx).data(item.uri).size(4096).build() }
        AsyncImage(
            model = request, contentDescription = item.name, contentScale = ContentScale.Fit,
            onSuccess = { s ->
                val d = s.result.drawable
                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) imgRatio = d.intrinsicWidth.toFloat() / d.intrinsicHeight
            },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
        if (abs(scale - 1f) > 0.01f) {
            Text("${(scale * 100).toInt()}%", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White, fontSize = 12.sp)
        }
    }
}
