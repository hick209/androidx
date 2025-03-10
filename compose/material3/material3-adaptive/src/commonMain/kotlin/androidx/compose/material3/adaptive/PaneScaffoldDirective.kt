/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.material3.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Calculates the standard [PaneScaffoldDirective] from a given [WindowAdaptiveInfo]. Use this
 * method with [calculateWindowAdaptiveInfo] to acquire Material-recommended adaptive layout
 * settings of the current activity window.
 *
 * See more details on the [Material design guideline site]
 * (https://m3.material.io/foundations/layout/applying-layout/window-size-classes).
 *
 * @param windowAdaptiveInfo [WindowAdaptiveInfo] that collects useful information in making
 *        layout adaptation decisions like [WindowSizeClass].
 * @param verticalHingePolicy [HingePolicy] that decides how layouts are supposed to address
 *        vertical hinges.
 * @return an [PaneScaffoldDirective] to be used to decide adaptive layout states.
 */
// TODO(b/285144647): Add more details regarding the use scenarios of this function.
@ExperimentalMaterial3AdaptiveApi
fun calculateStandardPaneScaffoldDirective(
    windowAdaptiveInfo: WindowAdaptiveInfo,
    verticalHingePolicy: HingePolicy = HingePolicy.AvoidSeparating
): PaneScaffoldDirective {
    val maxHorizontalPartitions: Int
    val contentPadding: PaddingValues
    val verticalSpacerSize: Dp
    when (windowAdaptiveInfo.windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            maxHorizontalPartitions = 1
            contentPadding = PaddingValues(16.dp)
            verticalSpacerSize = 0.dp
        }
        WindowWidthSizeClass.Medium -> {
            maxHorizontalPartitions = 1
            contentPadding = PaddingValues(24.dp)
            verticalSpacerSize = 0.dp
        }
        else -> {
            maxHorizontalPartitions = 2
            contentPadding = PaddingValues(24.dp)
            verticalSpacerSize = 24.dp
        }
    }
    val maxVerticalPartitions: Int
    val horizontalSpacerSize: Dp

    // TODO(conradchen): Confirm the table top mode settings
    if (windowAdaptiveInfo.posture.isTabletop) {
        maxVerticalPartitions = 2
        horizontalSpacerSize = 24.dp
    } else {
        maxVerticalPartitions = 1
        horizontalSpacerSize = 0.dp
    }

    return PaneScaffoldDirective(
        maxHorizontalPartitions,
        GutterSizes(contentPadding, verticalSpacerSize, horizontalSpacerSize),
        maxVerticalPartitions,
        getExcludedVerticalBounds(windowAdaptiveInfo.posture, verticalHingePolicy)
    )
}

/**
 * Calculates the dense-mode [PaneScaffoldDirective] from a given [WindowAdaptiveInfo]. Use this
 * method with [calculateWindowAdaptiveInfo] to acquire Material-recommended dense-mode adaptive
 * layout settings of the current activity window.
 *
 * See more details on the [Material design guideline site]
 * (https://m3.material.io/foundations/layout/applying-layout/window-size-classes).
 *
 * @param windowAdaptiveInfo [WindowAdaptiveInfo] that collects useful information in making
 *        layout adaptation decisions like [WindowSizeClass].
 * @param verticalHingePolicy [HingePolicy] that decides how layouts are supposed to address
 *        vertical hinges.
 * @return an [PaneScaffoldDirective] to be used to decide adaptive layout states.
 */
// TODO(b/285144647): Add more details regarding the use scenarios of this function.
@ExperimentalMaterial3AdaptiveApi
fun calculateDensePaneScaffoldDirective(
    windowAdaptiveInfo: WindowAdaptiveInfo,
    verticalHingePolicy: HingePolicy = HingePolicy.AvoidSeparating
): PaneScaffoldDirective {
    val maxHorizontalPartitions: Int
    val contentPadding: PaddingValues
    val verticalSpacerSize: Dp
    when (windowAdaptiveInfo.windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            maxHorizontalPartitions = 1
            contentPadding = PaddingValues(16.dp)
            verticalSpacerSize = 0.dp
        }
        WindowWidthSizeClass.Medium -> {
            maxHorizontalPartitions = 2
            contentPadding = PaddingValues(24.dp)
            verticalSpacerSize = 24.dp
        }
        else -> {
            maxHorizontalPartitions = 2
            contentPadding = PaddingValues(24.dp)
            verticalSpacerSize = 24.dp
        }
    }
    val maxVerticalPartitions: Int
    val horizontalSpacerSize: Dp

    if (windowAdaptiveInfo.posture.isTabletop) {
        maxVerticalPartitions = 2
        horizontalSpacerSize = 24.dp
    } else {
        maxVerticalPartitions = 1
        horizontalSpacerSize = 0.dp
    }

    return PaneScaffoldDirective(
        maxHorizontalPartitions,
        GutterSizes(contentPadding, verticalSpacerSize, horizontalSpacerSize),
        maxVerticalPartitions,
        getExcludedVerticalBounds(windowAdaptiveInfo.posture, verticalHingePolicy)
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun getExcludedVerticalBounds(posture: Posture, hingePolicy: HingePolicy): List<Rect> {
    return when (hingePolicy) {
        HingePolicy.AvoidSeparating -> posture.separatingVerticalHingeBounds
        HingePolicy.AvoidOccluding -> posture.occludingVerticalHingeBounds
        HingePolicy.AlwaysAvoid -> posture.allVerticalHingeBounds
        else -> emptyList()
    }
}

/**
 * Top-level directives about how a pane scaffold should be arranged and spaced, like how many
 * partitions the layout can be split into and what should be the gutter size.
 *
 * @constructor create an instance of [PaneScaffoldDirective]
 * @param maxHorizontalPartitions the max number of partitions along the horizontal axis the layout
 *        can be split into.
 * @param gutterSizes the gutter sizes between panes the layout should preserve.
 * @param maxVerticalPartitions the max number of partitions along the vertical axis the layout can
 *        be split into.
 * @param excludedBounds the bounds of all areas in the window that the layout needs to avoid
 *        displaying anything upon it. Usually these bounds represent where physical hinges are.
 */
@ExperimentalMaterial3AdaptiveApi
@Immutable
class PaneScaffoldDirective(
    val maxHorizontalPartitions: Int,
    val gutterSizes: GutterSizes,
    val maxVerticalPartitions: Int,
    val excludedBounds: List<Rect>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaneScaffoldDirective) return false
        if (maxHorizontalPartitions != other.maxHorizontalPartitions) return false
        if (gutterSizes != other.gutterSizes) return false
        if (maxVerticalPartitions != other.maxVerticalPartitions) return false
        return true
    }

    override fun hashCode(): Int {
        var result = maxHorizontalPartitions
        result = 31 * result + gutterSizes.hashCode()
        result = 31 * result + maxVerticalPartitions
        return result
    }
}

/**
 * Denotes the gutter sizes of an adaptive layout. Gutters of an adaptive layouts include spacers
 * between panes ([verticalSpacerSize] and [horizontalSpacerSize]) and paddings of the layout itself
 * ([contentPadding]). Usually we will expect larger gutter sizes to be set when the layout is
 * larger and more panes are shown in the layout.
 *
 * @constructor create an instance of [GutterSizes]
 * @param contentPadding Size of the paddings between the panes and the outer bounds of the layout.
 * @param verticalSpacerSize Size of the vertical spacers between panes. It's similar to left/right
 *        margins of the layout's children.
 * @param horizontalSpacerSize Size of the horizontal spacers between panes. It's similar to
 *        top/bottom margins of the layout's children.
 */
@ExperimentalMaterial3AdaptiveApi
@Immutable
class GutterSizes(
    val contentPadding: PaddingValues,
    val verticalSpacerSize: Dp,
    val horizontalSpacerSize: Dp = verticalSpacerSize
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GutterSizes) return false
        if (contentPadding != other.contentPadding) return false
        if (verticalSpacerSize != other.verticalSpacerSize) return false
        if (horizontalSpacerSize != other.horizontalSpacerSize) return false
        return true
    }

    override fun hashCode(): Int {
        var result = contentPadding.hashCode()
        result = 31 * result + verticalSpacerSize.hashCode()
        result = 31 * result + horizontalSpacerSize.hashCode()
        return result
    }
}

/** Policies that indicate how hinges are supposed to be addressed in an adaptive layout. */
@Immutable
@kotlin.jvm.JvmInline
value class HingePolicy private constructor(private val value: Int) {
    override fun toString(): String {
        return "HingePolicy." + when (this) {
            AlwaysAvoid -> "AlwaysAvoid"
            AvoidSeparating -> "AvoidOccludingAndSeparating"
            AvoidOccluding -> "AvoidOccludingOnly"
            NeverAvoid -> "NeverAvoid"
            else -> ""
        }
    }

    companion object {
        /** When rendering content in a layout, always avoid where hinges are. */
        val AlwaysAvoid = HingePolicy(0)
        /**
         * When rendering content in a layout, avoid hinges that are separating. Note that an
         * occluding hinge is supposed to be separating as well but not vice versa.
         */
        val AvoidSeparating = HingePolicy(1)
        /**
         * When rendering content in a layout, avoid hinges that are occluding. Note that an
         * occluding hinge is supposed to be separating as well but not vice versa.
         */
        val AvoidOccluding = HingePolicy(2)
        /** When rendering content in a layout, never avoid any hinges, separating or not. */
        val NeverAvoid = HingePolicy(3)
    }
}
