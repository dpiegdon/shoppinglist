package org.p23q.shoppinglist.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/**
 * Real drag-reorder by a handle (T-30), shared by a list's categories and the accounts (T-307).
 * Dragging the handle starts immediately (no long-press) and the dragged item follows the finger
 * (translationY + raised zIndex); once it has travelled one step it swaps with its neighbour via
 * [onMove] and the offset is rebased by that step, so the motion stays continuous. Items are keyed,
 * and the gesture reads its item's *current* index live, so the handle keeps following its item
 * across swaps.
 *
 * The step is [fixedStepPx] when given (the categories' rows), otherwise the neighbour's measured
 * height plus [gapPx] — the distance the dragged item's own place moves by when they swap.
 * [canMoveTo] limits where an item may go: the accounts keep the local area last.
 */
@Stable
class DragReorderState<K> internal constructor(
    private val keys: State<List<K>>,
    private val onMove: State<(from: Int, to: Int) -> Unit>,
    private val canMoveTo: State<(to: Int) -> Boolean>,
    private val fixedStepPx: Float?,
    private val gapPx: Float,
) {
    /** The item being dragged, if any. */
    var dragging by mutableStateOf<K?>(null)
        private set

    internal var offset by mutableFloatStateOf(0f)
    internal val heights = mutableStateMapOf<K, Int>()

    fun isDragging(key: K): Boolean = dragging == key

    internal fun start(key: K) {
        dragging = key
        offset = 0f
    }

    internal fun stop() {
        dragging = null
        offset = 0f
    }

    internal fun dragBy(key: K, dy: Float) {
        offset += dy
        val current = keys.value
        val idx = current.indexOf(key)
        if (idx < 0) return
        val up = idx - 1
        val down = idx + 1
        if (up >= 0 && canMoveTo.value(up) && offset <= -step(current[up])) {
            val by = step(current[up])
            onMove.value(idx, up)
            offset += by
        } else if (down < current.size && canMoveTo.value(down) && offset >= step(current[down])) {
            val by = step(current[down])
            onMove.value(idx, down)
            offset -= by
        }
    }

    private fun step(neighbour: K): Float = fixedStepPx ?: ((heights[neighbour] ?: 0) + gapPx)
}

@Composable
fun <K> rememberDragReorderState(
    keys: List<K>,
    onMove: (from: Int, to: Int) -> Unit,
    canMoveTo: (to: Int) -> Boolean = { true },
    fixedStepPx: Float? = null,
    gapPx: Float = 0f,
): DragReorderState<K> {
    val currentKeys = rememberUpdatedState(keys)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentCanMoveTo = rememberUpdatedState(canMoveTo)
    return remember(fixedStepPx, gapPx) {
        DragReorderState(currentKeys, currentOnMove, currentCanMoveTo, fixedStepPx, gapPx)
    }
}

/** On the item: it follows the finger above its neighbours while dragged, and its height is known. */
fun <K> Modifier.dragReorderItem(state: DragReorderState<K>, key: K): Modifier =
    zIndex(if (state.isDragging(key)) 1f else 0f)
        .graphicsLayer { translationY = if (state.isDragging(key)) state.offset else 0f }
        .onSizeChanged { state.heights[key] = it.height }

/** On the handle: dragging it moves the item. */
fun <K> Modifier.dragReorderHandle(state: DragReorderState<K>, key: K): Modifier =
    pointerInput(key) {
        detectDragGestures(
            onDragStart = { state.start(key) },
            onDragEnd = { state.stop() },
            onDragCancel = { state.stop() },
        ) { change, dragAmount ->
            change.consume()
            state.dragBy(key, dragAmount.y)
        }
    }
