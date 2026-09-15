package com.mockonomy.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mockonomy.R

/**
 * The design uses Phosphor Icons; Material's glyphs are heavier and rounder, so mixing them
 * stood out. The icons in use are embedded as `res/drawable/ic_ph_*.xml` (MIT,
 * THIRD_PARTY_LICENSES.md).
 *
 * When a new icon is needed, do not convert the SVG by hand: `scripts/phosphor.py` does the
 * same conversion.
 */
object MockonomyIcons {
    @DrawableRes val ArrowLeft = R.drawable.ic_ph_arrow_left
    @DrawableRes val ArrowRight = R.drawable.ic_ph_arrow_right
    @DrawableRes val ArrowsLeftRight = R.drawable.ic_ph_arrows_left_right
    @DrawableRes val BracketsCurly = R.drawable.ic_ph_brackets_curly
    @DrawableRes val CaretDown = R.drawable.ic_ph_caret_down
    @DrawableRes val CaretRight = R.drawable.ic_ph_caret_right
    @DrawableRes val CheckCircle = R.drawable.ic_ph_check_circle
    @DrawableRes val CheckCircleFill = R.drawable.ic_ph_check_circle_fill
    @DrawableRes val CircleDashed = R.drawable.ic_ph_circle_dashed
    @DrawableRes val ClipboardText = R.drawable.ic_ph_clipboard_text
    @DrawableRes val Copy = R.drawable.ic_ph_copy
    @DrawableRes val DotsThreeVertical = R.drawable.ic_ph_dots_three_vertical
    @DrawableRes val FileArrowUp = R.drawable.ic_ph_file_arrow_up
    @DrawableRes val FileArrowDown = R.drawable.ic_ph_file_arrow_down
    @DrawableRes val Gear = R.drawable.ic_ph_gear
    @DrawableRes val FileCode = R.drawable.ic_ph_file_code
    @DrawableRes val Funnel = R.drawable.ic_ph_funnel
    @DrawableRes val GitBranch = R.drawable.ic_ph_git_branch
    @DrawableRes val Info = R.drawable.ic_ph_info
    @DrawableRes val MagnifyingGlass = R.drawable.ic_ph_magnifying_glass
    @DrawableRes val PlayFill = R.drawable.ic_ph_play_fill
    @DrawableRes val PlugsConnectedFill = R.drawable.ic_ph_plugs_connected_fill
    @DrawableRes val Plus = R.drawable.ic_ph_plus
    @DrawableRes val Pulse = R.drawable.ic_ph_pulse
    @DrawableRes val Question = R.drawable.ic_ph_question
    @DrawableRes val Stack = R.drawable.ic_ph_stack
    @DrawableRes val StopFill = R.drawable.ic_ph_stop_fill
    @DrawableRes val Swap = R.drawable.ic_ph_swap
    @DrawableRes val Warning = R.drawable.ic_ph_warning
    @DrawableRes val X = R.drawable.ic_ph_x
    @DrawableRes val XCircleFill = R.drawable.ic_ph_x_circle_fill
}

/**
 * Icon sizes from the design: 14dp inline, 18dp in body text, 22dp for the empty-state
 * glyph. The default is 18dp — every app bar and row-end icon uses it.
 */
@Composable
fun MockonomyIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MockonomyTheme.colors.onSurfaceVariant,
    size: Dp = 18.dp,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
