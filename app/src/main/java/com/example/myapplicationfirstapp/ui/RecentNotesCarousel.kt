package com.example.myapplicationfirstapp.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.carousel.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.formatNoteTime
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Source-aligned carousel layout; note content replaces album content.
 * The caller MUST pass active=false behind editors, sheets, search, or offscreen content.
 * This component never records usage: a deliberate click delegates to the existing opener.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentNotesCarousel(
    notes: List<Note>,
    imageFile: (String) -> File?,
    onOpen: (String) -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousIndex by rememberSaveable { mutableIntStateOf(0) }
    if (notes.isEmpty()) return

    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.toFloat()
    val height = configuration.screenHeightDp.toFloat()
    if (width <= 0f || height <= 0f) return
    val spec = recentNotesLayoutSpec(width, height)
    val ids = notes.map { it.id }
    val initialIndex = recentNotesIndexAfterChange(ids, selectedNoteId, previousIndex)
    val resumed = recentCarouselLifecycleResumed()
    val english = LocalStrings.current.english

    // An order change creates a new carousel at the retained ID, not at index zero.
    // Its item-count lambda captures the immutable order of THIS keyed state.
    key(ids) {
        val carousel = rememberCarouselState(initialItem = initialIndex) { ids.size }
        LaunchedEffect(carousel) {
            snapshotFlow { carousel.currentItem }.collect { index ->
                previousIndex = index
                selectedNoteId = ids.getOrNull(index)
            }
        }
        LaunchedEffect(carousel, active, resumed, spec.tablet) {
            if (!active || !resumed || ids.size < 2) return@LaunchedEffect
            while (isActive) {
                delay(4500)
                if (!carousel.isScrollInProgress) {
                    carousel.animateScrollToItem(
                        (carousel.currentItem + 1) % ids.size,
                        animationSpec = tween(if (spec.tablet) 800 else 900)
                    )
                }
            }
        }

        if (spec.tablet) {
            Column(
                modifier = modifier.fillMaxWidth().padding(
                    top = spec.topPaddingDp.dp,
                    bottom = spec.bottomPaddingDp.dp
                )
            ) {
                HorizontalMultiBrowseCarousel(
                    state = carousel,
                    preferredItemWidth = spec.itemWidthDp.dp,
                    itemSpacing = 16.dp,
                    contentPadding = PaddingValues(horizontal = spec.horizontalPaddingDp.dp),
                    minSmallItemWidth = 48.dp,
                    maxSmallItemWidth = 140.dp,
                    modifier = Modifier.fillMaxWidth().height(spec.heightDp.dp)
                ) { index ->
                    val note = notes.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
                    val shape = MaterialTheme.shapes.extraLarge
                    Card(
                        onClick = { onOpen(note.id) },
                        modifier = Modifier.fillMaxSize().maskClip(shape),
                        shape = shape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        RecentNoteCarouselContent(
                            note = note,
                            file = note.images.firstOrNull()?.let(imageFile),
                            spec = spec,
                            english = english,
                            onOpen = { onOpen(note.id) }
                        )
                    }
                }
            }
        } else {
            Box(modifier = modifier.fillMaxWidth().height(spec.heightDp.dp)) {
                HorizontalUncontainedCarousel(
                    state = carousel,
                    itemWidth = spec.itemWidthDp.dp,
                    itemSpacing = 0.dp,
                    contentPadding = PaddingValues(0.dp),
                    flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(carousel),
                    modifier = Modifier.fillMaxSize()
                ) { index ->
                    val note = notes.getOrNull(index) ?: return@HorizontalUncontainedCarousel
                    Box(
                        modifier = Modifier.fillMaxSize().clip(RectangleShape)
                            .clickable { onOpen(note.id) }
                    ) {
                        RecentNoteCarouselContent(
                            note = note,
                            file = note.images.firstOrNull()?.let(imageFile),
                            spec = spec,
                            english = english,
                            onOpen = { onOpen(note.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentNoteCarouselContent(
    note: Note,
    file: File?,
    spec: RecentNotesLayoutSpec,
    english: Boolean,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val gradient = if (spec.tablet) {
        listOf(Color.Transparent, Color.Transparent, colors.surface.copy(alpha = 0.35f),
            colors.surface.copy(alpha = 0.75f), colors.surface.copy(alpha = 0.95f))
    } else {
        listOf(colors.background, colors.background.copy(alpha = 0.6f), Color.Transparent,
            Color.Transparent, colors.background.copy(alpha = 0.6f),
            colors.background.copy(alpha = 0.95f), colors.background)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        RecentNoteCarouselCover(note, file, spec)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(gradient)))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(if (spec.tablet) 16.dp else 24.dp)
        ) {
            Text(
                text = note.displayTitle,
                style = if (spec.tablet) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (spec.tablet) colors.onSurface else colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(if (spec.tablet) 2.dp else 4.dp))
                Text(
                    text = note.preview,
                    style = if (spec.tablet) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(if (spec.tablet) 12.dp else 24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer
                    ),
                    contentPadding = if (spec.tablet) PaddingValues(20.dp, 10.dp)
                        else PaddingValues(32.dp, 16.dp),
                    modifier = Modifier.height(if (spec.tablet) 44.dp else 56.dp)
                ) {
                    Text(
                        text = if (english) "Open note" else "打开笔记",
                        style = if (spec.tablet) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.titleMedium,
                        fontWeight = if (spec.tablet) FontWeight.Bold else FontWeight.ExtraBold
                    )
                }
                Text(
                    text = formatNoteTime(note.updatedAt, english),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Shared File-only loader; no hidden medium-shape clip from AttachmentImage. */
@Composable
private fun RecentNoteCarouselCover(note: Note, file: File?, spec: RecentNotesLayoutSpec) {
    val colors = MaterialTheme.colorScheme
    val icon = when {
        note.checklist -> Icons.Filled.Checklist
        note.images.isNotEmpty() -> Icons.Filled.Image
        note.remindAt != null -> Icons.Filled.Notifications
        else -> Icons.Filled.Description
    }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onPrimaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.size(if (spec.tablet) 72.dp else 112.dp)
        )
        if (file != null) {
            val context = LocalContext.current
            val target = with(LocalDensity.current) {
                maxOf(spec.itemWidthDp.dp.toPx(), spec.heightDp.dp.toPx())
                    .roundToInt().coerceIn(1, 1600)
            }
            val stamp = attachmentImageCacheKey(file)
            val request = remember(context, stamp, target) {
                ImageRequest.Builder(context.applicationContext)
                    .data(file).size(target, target).scale(Scale.FILL)
                    .precision(Precision.INEXACT).allowHardware(false)
                    .memoryCacheKey("$stamp|carousel-crop|$target").build()
            }
            val painter = rememberAsyncImagePainter(
                model = request,
                imageLoader = AttachmentImageLoader.get(context),
                contentScale = ContentScale.Crop
            )
            if (painter.state is AsyncImagePainter.State.Success) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** Uses the already-present lifecycle-runtime dependency, not lifecycle-runtime-compose. */
@Composable
private fun recentCarouselLifecycleResumed(): Boolean {
    val context = LocalContext.current
    val owner = remember(context) { context.recentCarouselLifecycleOwner() }
    var resumed by remember(owner) {
        mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
    }
    DisposableEffect(owner) {
        val lifecycle = owner?.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        }
        lifecycle?.addObserver(observer)
        resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return resumed
}

private fun Context.recentCarouselLifecycleOwner(): LifecycleOwner? {
    var current: Context = this
    while (true) {
        if (current is LifecycleOwner) return current
        val base = (current as? ContextWrapper)?.baseContext ?: return null
        if (base === current) return null
        current = base
    }
}
