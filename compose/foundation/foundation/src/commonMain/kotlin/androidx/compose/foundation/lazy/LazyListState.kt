/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.lazy

import androidx.annotation.IntRange as AndroidXIntRange
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.layout.AwaitFirstLayoutModifier
import androidx.compose.foundation.lazy.layout.LazyLayoutBeyondBoundsInfo
import androidx.compose.foundation.lazy.layout.LazyLayoutPinnedItemList
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.animateScrollToItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Creates a [LazyListState] that is remembered across compositions.
 *
 * Changes to the provided initial values will **not** result in the state being recreated or
 * changed in any way if it has already been created.
 *
 * @param initialFirstVisibleItemIndex the initial value for [LazyListState.firstVisibleItemIndex]
 * @param initialFirstVisibleItemScrollOffset the initial value for
 * [LazyListState.firstVisibleItemScrollOffset]
 */
@Composable
fun rememberLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0
): LazyListState {
    return rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            initialFirstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset
        )
    }
}

/**
 * A state object that can be hoisted to control and observe scrolling.
 *
 * In most cases, this will be created via [rememberLazyListState].
 *
 * @param firstVisibleItemIndex the initial value for [LazyListState.firstVisibleItemIndex]
 * @param firstVisibleItemScrollOffset the initial value for
 * [LazyListState.firstVisibleItemScrollOffset]
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
class LazyListState constructor(
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0
) : ScrollableState {

    internal var hasLookaheadPassOccurred: Boolean = false
        private set
    internal var postLookaheadLayoutInfo: LazyListLayoutInfo? = null
        private set

    /**
     * The holder class for the current scroll position.
     */
    private val scrollPosition =
        LazyListScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

    private val animateScrollScope = LazyListAnimateScrollScope(this)

    /**
     * The index of the first item that is visible.
     *
     * Note that this property is observable and if you use it in the composable function it will
     * be recomposed on every change causing potential performance issues.
     *
     * If you want to run some side effects like sending an analytics event or updating a state
     * based on this value consider using "snapshotFlow":
     * @sample androidx.compose.foundation.samples.UsingListScrollPositionForSideEffectSample
     *
     * If you need to use it in the composition then consider wrapping the calculation into a
     * derived state in order to only have recompositions when the derived value changes:
     * @sample androidx.compose.foundation.samples.UsingListScrollPositionInCompositionSample
     */
    val firstVisibleItemIndex: Int get() = scrollPosition.index

    /**
     * The scroll offset of the first visible item. Scrolling forward is positive - i.e., the
     * amount that the item is offset backwards.
     *
     * Note that this property is observable and if you use it in the composable function it will
     * be recomposed on every scroll causing potential performance issues.
     * @see firstVisibleItemIndex for samples with the recommended usage patterns.
     */
    val firstVisibleItemScrollOffset: Int get() = scrollPosition.scrollOffset

    /** Backing state for [layoutInfo] */
    private val layoutInfoState = mutableStateOf<LazyListLayoutInfo>(EmptyLazyListLayoutInfo)

    /**
     * The object of [LazyListLayoutInfo] calculated during the last layout pass. For example,
     * you can use it to calculate what items are currently visible.
     *
     * Note that this property is observable and is updated after every scroll or remeasure.
     * If you use it in the composable function it will be recomposed on every change causing
     * potential performance issues including infinity recomposition loop.
     * Therefore, avoid using it in the composition.
     *
     * If you want to run some side effects like sending an analytics event or updating a state
     * based on this value consider using "snapshotFlow":
     * @sample androidx.compose.foundation.samples.UsingListLayoutInfoForSideEffectSample
     */
    val layoutInfo: LazyListLayoutInfo get() = layoutInfoState.value

    /**
     * [InteractionSource] that will be used to dispatch drag events when this
     * list is being dragged. If you want to know whether the fling (or animated scroll) is in
     * progress, use [isScrollInProgress].
     */
    val interactionSource: InteractionSource get() = internalInteractionSource

    internal val internalInteractionSource: MutableInteractionSource = MutableInteractionSource()

    /**
     * The amount of scroll to be consumed in the next layout pass.  Scrolling forward is negative
     * - that is, it is the amount that the items are offset in y
     */
    internal var scrollToBeConsumed = 0f
        private set

    /**
     * Needed for [animateScrollToItem].  Updated on every measure.
     */
    internal var density: Density = Density(1f, 1f)

    /**
     * The ScrollableController instance. We keep it as we need to call stopAnimation on it once
     * we reached the end of the list.
     */
    private val scrollableState = ScrollableState { -onScroll(-it) }

    /**
     * Only used for testing to confirm that we're not making too many measure passes
     */
    /*@VisibleForTesting*/
    internal var numMeasurePasses: Int = 0
        private set

    /**
     * Only used for testing to disable prefetching when needed to test the main logic.
     */
    /*@VisibleForTesting*/
    internal var prefetchingEnabled: Boolean = true

    /**
     * The index scheduled to be prefetched (or the last prefetched index if the prefetch is done).
     */
    private var indexToPrefetch = -1

    /**
     * The handle associated with the current index from [indexToPrefetch].
     */
    private var currentPrefetchHandle: LazyLayoutPrefetchState.PrefetchHandle? = null

    /**
     * Keeps the scrolling direction during the previous calculation in order to be able to
     * detect the scrolling direction change.
     */
    private var wasScrollingForward = false

    /**
     * The [Remeasurement] object associated with our layout. It allows us to remeasure
     * synchronously during scroll.
     */
    internal var remeasurement: Remeasurement? = null
        private set

    /**
     * The modifier which provides [remeasurement].
     */
    internal val remeasurementModifier = object : RemeasurementModifier {
        override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
            this@LazyListState.remeasurement = remeasurement
        }
    }

    /**
     * Provides a modifier which allows to delay some interactions (e.g. scroll)
     * until layout is ready.
     */
    internal val awaitLayoutModifier = AwaitFirstLayoutModifier()

    internal val itemAnimator = LazyListItemAnimator()

    internal val beyondBoundsInfo = LazyLayoutBeyondBoundsInfo()

    /**
     * Constraints passed to the prefetcher for premeasuring the prefetched items.
     */
    internal var premeasureConstraints = Constraints()

    /**
     * Stores currently pinned items which are always composed.
     */
    internal val pinnedItems = LazyLayoutPinnedItemList()

    internal val nearestRange: IntRange by scrollPosition.nearestRangeState

    /**
     * Instantly brings the item at [index] to the top of the viewport, offset by [scrollOffset]
     * pixels.
     *
     * @param index the index to which to scroll. Must be non-negative.
     * @param scrollOffset the offset that the item should end up after the scroll. Note that
     * positive offset refers to forward scroll, so in a top-to-bottom list, positive offset will
     * scroll the item further upward (taking it partly offscreen).
     */
    suspend fun scrollToItem(
        @AndroidXIntRange(from = 0)
        index: Int,
        scrollOffset: Int = 0
    ) {
        scroll {
            snapToItemIndexInternal(index, scrollOffset)
        }
    }

    internal fun snapToItemIndexInternal(index: Int, scrollOffset: Int) {
        scrollPosition.requestPosition(index, scrollOffset)
        // placement animation is not needed because we snap into a new position.
        itemAnimator.reset()
        remeasurement?.forceRemeasure()
    }

    /**
     * Call this function to take control of scrolling and gain the ability to send scroll events
     * via [ScrollScope.scrollBy]. All actions that change the logical scroll position must be
     * performed within a [scroll] block (even if they don't call any other methods on this
     * object) in order to guarantee that mutual exclusion is enforced.
     *
     * If [scroll] is called from elsewhere, this will be canceled.
     */
    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit
    ) {
        awaitLayoutModifier.waitForFirstLayout()
        scrollableState.scroll(scrollPriority, block)
    }

    override fun dispatchRawDelta(delta: Float): Float =
        scrollableState.dispatchRawDelta(delta)

    override val isScrollInProgress: Boolean
        get() = scrollableState.isScrollInProgress

    override var canScrollForward: Boolean by mutableStateOf(false)
        private set
    override var canScrollBackward: Boolean by mutableStateOf(false)
        private set

    // TODO: Coroutine scrolling APIs will allow this to be private again once we have more
    //  fine-grained control over scrolling
    /*@VisibleForTesting*/
    internal fun onScroll(distance: Float): Float {
        if (distance < 0 && !canScrollForward || distance > 0 && !canScrollBackward) {
            return 0f
        }
        check(abs(scrollToBeConsumed) <= 0.5f) {
            "entered drag with non-zero pending scroll: $scrollToBeConsumed"
        }
        scrollToBeConsumed += distance

        // scrollToBeConsumed will be consumed synchronously during the forceRemeasure invocation
        // inside measuring we do scrollToBeConsumed.roundToInt() so there will be no scroll if
        // we have less than 0.5 pixels
        if (abs(scrollToBeConsumed) > 0.5f) {
            val preScrollToBeConsumed = scrollToBeConsumed
            remeasurement?.forceRemeasure()
            if (prefetchingEnabled) {
                notifyPrefetch(preScrollToBeConsumed - scrollToBeConsumed)
            }
        }

        // here scrollToBeConsumed is already consumed during the forceRemeasure invocation
        if (abs(scrollToBeConsumed) <= 0.5f) {
            // We consumed all of it - we'll hold onto the fractional scroll for later, so report
            // that we consumed the whole thing
            return distance
        } else {
            val scrollConsumed = distance - scrollToBeConsumed
            // We did not consume all of it - return the rest to be consumed elsewhere (e.g.,
            // nested scrolling)
            scrollToBeConsumed = 0f // We're not consuming the rest, give it back
            return scrollConsumed
        }
    }

    private fun notifyPrefetch(delta: Float) {
        if (!prefetchingEnabled) {
            return
        }
        val info = layoutInfo
        if (info.visibleItemsInfo.isNotEmpty()) {
            val scrollingForward = delta < 0
            val indexToPrefetch = if (scrollingForward) {
                info.visibleItemsInfo.last().index + 1
            } else {
                info.visibleItemsInfo.first().index - 1
            }
            if (indexToPrefetch != this.indexToPrefetch &&
                indexToPrefetch in 0 until info.totalItemsCount
            ) {
                if (wasScrollingForward != scrollingForward) {
                    // the scrolling direction has been changed which means the last prefetched
                    // is not going to be reached anytime soon so it is safer to dispose it.
                    // if this item is already visible it is safe to call the method anyway
                    // as it will be no-op
                    currentPrefetchHandle?.cancel()
                }
                this.wasScrollingForward = scrollingForward
                this.indexToPrefetch = indexToPrefetch
                currentPrefetchHandle = prefetchState.schedulePrefetch(
                    indexToPrefetch, premeasureConstraints
                )
            }
        }
    }

    private fun cancelPrefetchIfVisibleItemsChanged(info: LazyListLayoutInfo) {
        if (indexToPrefetch != -1 && info.visibleItemsInfo.isNotEmpty()) {
            val expectedPrefetchIndex = if (wasScrollingForward) {
                info.visibleItemsInfo.last().index + 1
            } else {
                info.visibleItemsInfo.first().index - 1
            }
            if (indexToPrefetch != expectedPrefetchIndex) {
                indexToPrefetch = -1
                currentPrefetchHandle?.cancel()
                currentPrefetchHandle = null
            }
        }
    }

    internal val prefetchState = LazyLayoutPrefetchState()

    /**
     * Animate (smooth scroll) to the given item.
     *
     * @param index the index to which to scroll. Must be non-negative.
     * @param scrollOffset the offset that the item should end up after the scroll. Note that
     * positive offset refers to forward scroll, so in a top-to-bottom list, positive offset will
     * scroll the item further upward (taking it partly offscreen).
     */
    suspend fun animateScrollToItem(
        @AndroidXIntRange(from = 0)
        index: Int,
        scrollOffset: Int = 0
    ) {
        animateScrollScope.animateScrollToItem(
            index,
            scrollOffset,
            NumberOfItemsToTeleport,
            density
        )
    }

    /**
     *  Updates the state with the new calculated scroll position and consumed scroll.
     */
    internal fun applyMeasureResult(result: LazyListMeasureResult, isLookingAhead: Boolean) {
        if (!isLookingAhead && hasLookaheadPassOccurred) {
            // If there was already a lookahead pass, record this result as postLookahead result
            postLookaheadLayoutInfo = result
        } else {
            if (isLookingAhead) {
                hasLookaheadPassOccurred = true
            }
            scrollPosition.updateFromMeasureResult(result)
            scrollToBeConsumed -= result.consumedScroll
            layoutInfoState.value = result

            canScrollForward = result.canScrollForward
            canScrollBackward = (result.firstVisibleItem?.index ?: 0) != 0 ||
                result.firstVisibleItemScrollOffset != 0

            if (isLookingAhead) updateScrollDeltaForPostLookahead(result.scrollBackAmount)
            numMeasurePasses++

            cancelPrefetchIfVisibleItemsChanged(result)
        }
    }

    internal var coroutineScope: CoroutineScope? = null

    internal val scrollDeltaBetweenPasses: Float
        get() = _scrollDeltaBetweenPasses.value

    private var _scrollDeltaBetweenPasses: AnimationState<Float, AnimationVector1D> =
        AnimationState(Float.VectorConverter, 0f, 0f)

    // Updates the scroll delta between lookahead & post-lookahead pass
    private fun updateScrollDeltaForPostLookahead(delta: Float) {
        if (delta <= with(density) { DeltaThresholdForScrollAnimation.toPx() }) {
            // If the delta is within the threshold, scroll by the delta amount instead of animating
            return
        }

        // Scroll delta is updated during lookahead, we don't need to trigger lookahead when
        // the delta changes.
        Snapshot.withoutReadObservation {
            val currentDelta = _scrollDeltaBetweenPasses.value

            if (_scrollDeltaBetweenPasses.isRunning) {
                _scrollDeltaBetweenPasses = _scrollDeltaBetweenPasses.copy(currentDelta - delta)
                coroutineScope?.launch {
                    _scrollDeltaBetweenPasses.animateTo(
                        0f,
                        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f),
                        true
                    )
                }
            } else {
                _scrollDeltaBetweenPasses = AnimationState(Float.VectorConverter, -delta)
                coroutineScope?.launch {
                    _scrollDeltaBetweenPasses.animateTo(
                        0f,
                        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f),
                        true
                    )
                }
            }
        }
    }

    /**
     * When the user provided custom keys for the items we can try to detect when there were
     * items added or removed before our current first visible item and keep this item
     * as the first visible one even given that its index has been changed.
     */
    internal fun updateScrollPositionIfTheFirstItemWasMoved(
        itemProvider: LazyListItemProvider,
        firstItemIndex: Int = Snapshot.withoutReadObservation { scrollPosition.index }
    ): Int = scrollPosition.updateScrollPositionIfTheFirstItemWasMoved(itemProvider, firstItemIndex)

    companion object {
        /**
         * The default [Saver] implementation for [LazyListState].
         */
        val Saver: Saver<LazyListState, *> = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = {
                LazyListState(
                    firstVisibleItemIndex = it[0],
                    firstVisibleItemScrollOffset = it[1]
                )
            }
        )
    }
}

private val DeltaThresholdForScrollAnimation = 1.dp

private object EmptyLazyListLayoutInfo : LazyListLayoutInfo {
    override val visibleItemsInfo = emptyList<LazyListItemInfo>()
    override val viewportStartOffset = 0
    override val viewportEndOffset = 0
    override val totalItemsCount = 0
    override val viewportSize = IntSize.Zero
    override val orientation = Orientation.Vertical
    override val reverseLayout = false
    override val beforeContentPadding = 0
    override val afterContentPadding = 0
    override val mainAxisItemSpacing = 0
}

private const val NumberOfItemsToTeleport = 100
