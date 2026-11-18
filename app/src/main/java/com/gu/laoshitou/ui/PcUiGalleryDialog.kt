// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gu.laoshitou.R
import kotlin.math.max
import kotlin.math.min

private val PcUiImages = listOf(
    R.drawable.pc_ui_1,
    R.drawable.pc_ui_2,
    R.drawable.pc_ui_3,
    R.drawable.pc_ui_4,
    R.drawable.pc_ui_5,
)

private const val PcUiMaxScale = 5f

/** Keep 2K screenshots crisp, but cap 4K screenshots to avoid OOM. */
private const val PcUiDecodeMaxDimension = 2560

/**
 * Full-screen PC UI gallery:
 * - HorizontalPager switches the five screenshots.
 * - Each page supports pinch-to-zoom and pan via TransformableState + graphicsLayer.
 * - The pager only scrolls at 1x so single-finger swipes remain available.
 */
@Composable
fun PcUiGalleryDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(pageCount = { PcUiImages.size })
        val pageScales = remember { mutableStateMapOf<Int, Float>() }
        val currentScale = pageScales[pagerState.currentPage] ?: 1f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = currentScale <= 1.01f,
            ) { page ->
                PcUiZoomablePage(
                    resId = PcUiImages[page],
                    page = page,
                    onScaleChanged = { index, scale -> pageScales[index] = scale },
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PcUiImages.indices.forEach { index ->
                        val active = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(if (active) 8.dp else 6.dp)
                                .background(
                                    if (active) Color.White else Color.White.copy(alpha = 0.35f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${pagerState.currentPage + 1} / ${PcUiImages.size}" +
                        " · 双指缩放 · 1x 时可左右滑动",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PcUiZoomablePage(
    resId: Int,
    page: Int,
    onScaleChanged: (Int, Float) -> Unit,
) {
    val bitmap = rememberBoundedPcUiBitmap(resId)
    val painter: Painter = if (bitmap != null) {
        BitmapPainter(bitmap)
    } else {
        painterResource(resId)
    }
    val intrinsic = painter.intrinsicSize

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Same zoom/pan math as TransformableState, but a custom detector is used
    // so one-finger drags at 1x still reach the pager.
    fun applyTransform(zoomChange: Float, panChange: Offset) {
        val newScale = (scale * zoomChange).coerceIn(1f, PcUiMaxScale)
        scale = newScale
        offset = if (newScale <= 1f) {
            Offset.Zero
        } else {
            clampPcUiOffset(offset + panChange, newScale, intrinsic, containerSize)
        }
        onScaleChanged(page, newScale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                // Use a custom detector instead of Modifier.transformable:
                // single-finger pans at 1x must fall through to the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastCentroid: Offset? = null
                    var lastDistance = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val centroid = pressed.fold(Offset.Zero) { acc, change ->
                                acc + change.position
                            } / pressed.size.toFloat()
                            val distance = (pressed[0].position - pressed[1].position)
                                .getDistance()
                            val previousCentroid = lastCentroid
                            if (lastDistance > 0f && previousCentroid != null) {
                                applyTransform(
                                    zoomChange = distance / lastDistance,
                                    panChange = centroid - previousCentroid,
                                )
                            }
                            lastCentroid = centroid
                            lastDistance = distance
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && scale > 1f) {
                            val position = pressed[0].position
                            val previous = lastCentroid ?: position
                            applyTransform(
                                zoomChange = 1f,
                                panChange = position - previous,
                            )
                            lastCentroid = position
                            lastDistance = 0f
                            event.changes.forEach { it.consume() }
                        } else {
                            lastCentroid = null
                            lastDistance = 0f
                        }
                        if (event.changes.none { it.pressed }) {
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = "PC 端 UI 截图 ${page + 1}",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

private fun clampPcUiOffset(
    proposed: Offset,
    scale: Float,
    intrinsic: Size,
    container: IntSize,
): Offset {
    if (intrinsic.width.isNaN() || intrinsic.height.isNaN() ||
        container.width <= 0 || container.height <= 0
    ) {
        return proposed
    }
    if (intrinsic.width <= 0f || intrinsic.height <= 0f) {
        return proposed
    }
    val fit = min(
        container.width / intrinsic.width,
        container.height / intrinsic.height,
    )
    val displayedWidth = intrinsic.width * fit
    val displayedHeight = intrinsic.height * fit
    val maxX = ((displayedWidth * scale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((displayedHeight * scale - container.height) / 2f).coerceAtLeast(0f)
    return Offset(
        proposed.x.coerceIn(-maxX, maxX),
        proposed.y.coerceIn(-maxY, maxY),
    )
}

@Composable
private fun rememberBoundedPcUiBitmap(resId: Int): ImageBitmap? {
    val resources = LocalContext.current.resources
    return remember(resId) { decodeBoundedPcUiBitmap(resources, resId) }
}

private fun decodeBoundedPcUiBitmap(resources: Resources, resId: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sampleSize = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sampleSize > PcUiDecodeMaxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(resources, resId, options)?.asImageBitmap()
    } catch (error: Exception) {
        null
    }
}
