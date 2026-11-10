package com.example.myapplicationfirstapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 按需解码图片。
 *
 * 关键点是「采样」：一张 4000x3000 的照片直接读进内存要 48MB，
 * 这里只按显示尺寸的 2 倍左右解码，缩略图只要几百 KB。
 * Android 9+ 用 ImageDecoder，它会自动处理拍照的旋转信息（EXIF）。
 */
@Composable
fun rememberAttachmentBitmap(file: File, targetPx: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        file.path,
        file.lastModified(),
        targetPx
    ) {
        value = withContext(Dispatchers.IO) {
            decodeSampled(file, targetPx)?.asImageBitmap()
        }
    }
    return image
}

/** Shared local-file request; the legacy full-screen decoder remains unchanged. */
@Composable
private fun rememberAttachmentPainter(
    file: File,
    targetPx: Int,
    contentScale: ContentScale
): AsyncImagePainter {
    val context = LocalContext.current
    val stamp = attachmentImageCacheKey(file)
    val pixels = targetPx.coerceAtLeast(1)
    val request = remember(context, stamp, pixels) {
        ImageRequest.Builder(context.applicationContext)
            .data(file)
            .size(pixels, pixels)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCacheKey("$stamp|$pixels")
            .build()
    }
    return rememberAsyncImagePainter(
        model = request,
        imageLoader = AttachmentImageLoader.get(context),
        contentScale = contentScale
    )
}

/** Fixed-size thumbnail: same public API and Crop behaviour as the legacy view. */
@Composable
fun AttachmentImage(
    file: File,
    modifier: Modifier = Modifier,
    targetPx: Int = 400
) {
    val painter = rememberAttachmentPainter(file, targetPx, ContentScale.Crop)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        when (painter.state) {
            is AsyncImagePainter.State.Error -> Text(
                text = "图片不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            is AsyncImagePainter.State.Success -> Unit
            else -> Text(
                text = "…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Inline image: retain Fit and the existing aspect-ratio limits for this stage. */
@Composable
fun InlineAttachmentImage(
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var retry by remember(file.path) { mutableIntStateOf(0) }
    val painter = key(file.path, retry) {
        rememberAttachmentPainter(file, 1200, ContentScale.Fit)
    }
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    val ratio = drawable
        ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
        ?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.toFloat() }
        ?.coerceIn(0.35f, 2.5f)
        ?: 1.5f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        when (painter.state) {
            is AsyncImagePainter.State.Error -> TextButton(onClick = {
                retry++
            }) {
                Text("图片加载失败 · 重试")
            }
            is AsyncImagePainter.State.Success -> Unit
            else -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 全屏看图：左右滑动切换同一篇笔记里的多张图，双指缩放、双击放大。
 *
 * 直接铺在窗口最上层（不是 Dialog），这样黑色背景能一直铺到状态栏和导航栏后面，
 * 不会在底部露出一条 App 的底色。
 */
@Composable
fun ImagePager(
    files: List<File>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (files.isEmpty()) return

    BackHandler { onDismiss() }

    // 黑底上用深色状态栏图标会看不见，这里临时改成浅色
    val view = LocalView.current
    val activity = view.context.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false

        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, files.lastIndex)
    ) { files.size }

    var zoomed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(
                file = files[page],
                isCurrent = page == pagerState.currentPage,
                onZoomChanged = { zoom ->
                    if (page == pagerState.currentPage) zoomed = zoom
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (files.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${files.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/**
 * 单张可缩放图片。
 *
 * 手势这里没有直接用 detectTransformGestures，因为它会无条件吃掉拖动事件，
 * 导致「不缩放时左右滑」也被拦下来，翻不了页。
 * 所以手写一段：只有真的捏合了、或者当前已经是放大状态，才消费事件。
 */
@Composable
private fun ZoomableImage(
    file: File,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    val bitmap = rememberAttachmentBitmap(file, 2048)

    var scale by remember(file.path) { mutableFloatStateOf(1f) }
    var offset by remember(file.path) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 划走时把缩放还原，免得回来还是放大状态
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(isCurrent, scale) {
        if (isCurrent) onZoomChanged(scale > 1.01f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(viewSize) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    var pastSlop = false
                    var transforming = scale > 1.01f

                    while (true) {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastSlop) {
                            val zoomMotion = abs(1f - zoomChange) * viewSize.width
                            val panMotion = panChange.getDistance()
                            if (zoomMotion > viewConfiguration.touchSlop ||
                                panMotion > viewConfiguration.touchSlop
                            ) {
                                pastSlop = true
                            }
                        }

                        val pinching = zoomChange != 1f
                        if (pastSlop && (transforming || pinching)) {
                            transforming = true

                            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            val maxX = viewSize.width * (newScale - 1f) / 2f
                            val maxY = viewSize.height * (newScale - 1f) / 2f

                            offset = Offset(
                                x = (offset.x * (newScale / scale) + panChange.x)
                                    .coerceIn(-maxX, maxX),
                                y = (offset.y * (newScale / scale) + panChange.y)
                                    .coerceIn(-maxY, maxY)
                            )
                            scale = newScale

                            event.changes.forEach { change ->
                                if (change.pressed) change.consume()
                            }
                        }

                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(file.path) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null

    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    sampleSizeFor(info.size.width, info.size.height, targetPx)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            }
            BitmapFactory.decodeFile(file.path, options)
        }
    }.getOrNull()
}

private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0) return 1

    var sample = 1
    while (width / (sample * 2) >= targetPx || height / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return sample
}
