package com.uhmk.pos.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Coarse screen buckets, following the Material window size classes.
 *
 * Rolled by hand from the configuration rather than pulling in the window-size-class artifact —
 * the app only needs "is there room for two panes", and this keeps the dependency list short.
 */
enum class PosWindowSize {
    /** Phone in portrait. One pane; secondary controls have to fold away. */
    COMPACT,

    /** Large phone in landscape or a small tablet. */
    MEDIUM,

    /** Tablet. Enough width to show the order list and the payment panel side by side. */
    EXPANDED;

    val isCompact: Boolean get() = this == COMPACT

    /** Two panes are only worth it once there is genuinely room for both. */
    val supportsTwoPane: Boolean get() = this != COMPACT
}

@Composable
@ReadOnlyComposable
fun rememberWindowSize(): PosWindowSize {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> PosWindowSize.COMPACT
        widthDp < 840 -> PosWindowSize.MEDIUM
        else -> PosWindowSize.EXPANDED
    }
}

/** Screen height matters for the sell grid: short screens need denser cards to stay usable. */
@Composable
@ReadOnlyComposable
fun screenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

/**
 * Minimum product tile width for the sell grid.
 *
 * A phone previously fitted two 150dp columns, which showed about four products out of eighty-five.
 * Narrowing the tile to 112dp gives three columns on the same phone while keeping the name and
 * price legible, and tablets go wider again so tiles do not stretch into odd shapes.
 */
@Composable
@ReadOnlyComposable
fun productTileMinWidth(): Dp = when (rememberWindowSize()) {
    PosWindowSize.COMPACT -> 112.dp
    PosWindowSize.MEDIUM -> 140.dp
    PosWindowSize.EXPANDED -> 160.dp
}

/**
 * Width of the docked Current sale panel.
 *
 * A fixed 360dp looked right on a small tablet and cramped on a large one, where product names and
 * money were wrapping while the grid beside it had width to spare. Taking a share of the screen
 * instead lets it grow, and the clamp stops it from squeezing the product grid on a small tablet or
 * stretching into a mostly-empty column on a desk monitor.
 */
@Composable
@ReadOnlyComposable
fun cartPanelWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return (width * 0.36f).dp.coerceIn(340.dp, 480.dp)
}

/**
 * Width cap for the pop-up Current sale sheet.
 *
 * Material's default bottom-sheet cap is 640dp, which on a tablet puts the order list and the
 * payment fields into two narrow columns with wrapped labels. The sheet is the whole checkout, so
 * it is allowed most of the screen.
 */
@Composable
@ReadOnlyComposable
fun cartSheetMaxWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return when (rememberWindowSize()) {
        PosWindowSize.COMPACT -> Dp.Unspecified
        PosWindowSize.MEDIUM -> (width * 0.94f).dp
        PosWindowSize.EXPANDED -> (width * 0.88f).dp.coerceAtMost(1000.dp)
    }
}
