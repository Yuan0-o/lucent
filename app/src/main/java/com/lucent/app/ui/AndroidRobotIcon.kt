package com.lucent.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The classic Android-robot ("bugdroid") mascot, hand-built as a single-colour ImageVector so it
 * tints exactly like any Material icon in the nav bar — no bitmap, no green fill, no cut-out tricks
 * that would fight the tint.
 *
 * This is the *solid* silhouette from the reference art (a filled dome head, a filled body, two
 * filled arms), rather than the earlier thin outline. The only "holes" are the two eyes: they're
 * drawn as sub-paths inside the head path with [PathFillType.EvenOdd], so wherever the head overlaps
 * an eye circle the fill cancels out and the animated glass background shows straight through — the
 * eyes read as the robot's own colour-less eyes on any palette, in light or dark, with no second
 * colour needed.
 *
 * Everything is authored in the vector's own black source colour and recoloured by the [Icon] tint
 * at draw time. Laid out on a 24x24 canvas to match Material, sized to fill the tab's vertical space:
 *  - two straight antennas angling up and outward,
 *  - a wide domed (semi-elliptical) head with a flat bottom and two punched-out eyes,
 *  - a rounded-bottom rectangular body directly below,
 *  - two thick, round-capped arm bars, one just outside each side of the body.
 */
val AndroidRobotIcon: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "AndroidRobot",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Two antennas, spread to line up with the wider head below.
        path(
            stroke = ink,
            strokeLineWidth = 1.3f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(8.0f, 2.5f); lineTo(9.7f, 5.2f)     // left
            moveTo(16.0f, 2.5f); lineTo(14.3f, 5.2f)   // right
        }

        // Filled domed head (a wide half-ellipse with a flat bottom edge) with the two eyes punched
        // out as holes via the even-odd rule: the eye circles sit inside the dome, so their overlap
        // cancels the fill and the background shows through.
        path(fill = ink, pathFillType = PathFillType.EvenOdd) {
            // Dome: from the left of the flat bottom, arc over the top to the right, then close the
            // flat bottom back to the start.
            moveTo(5.8f, 10.0f)
            arcTo(6.2f, 5.0f, 0f, false, true, 18.2f, 10.0f)
            close()
            // Left eye (centre 9.65, 7.9 · r 0.95), drawn as two half-arcs = a full circle.
            moveTo(10.6f, 7.9f)
            arcTo(0.95f, 0.95f, 0f, false, true, 8.7f, 7.9f)
            arcTo(0.95f, 0.95f, 0f, false, true, 10.6f, 7.9f)
            close()
            // Right eye (centre 14.35, 7.9 · r 0.95).
            moveTo(15.3f, 7.9f)
            arcTo(0.95f, 0.95f, 0f, false, true, 13.4f, 7.9f)
            arcTo(0.95f, 0.95f, 0f, false, true, 15.3f, 7.9f)
            close()
        }

        // Filled body: a rectangle with square top corners and rounded bottom corners — the tallest
        // element, which gives the icon its height.
        path(fill = ink, pathFillType = PathFillType.NonZero, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6.2f, 11.2f)
            lineTo(17.8f, 11.2f)
            lineTo(17.8f, 18.0f)
            arcTo(1.5f, 1.5f, 0f, false, true, 16.3f, 19.5f)
            lineTo(7.7f, 19.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6.2f, 18.0f)
            close()
        }

        // Two thick, round-capped arm bars, one just outside each side of the body.
        path(
            stroke = ink,
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(4.35f, 12.4f); lineTo(4.35f, 16.6f)      // left
            moveTo(19.65f, 12.4f); lineTo(19.65f, 16.6f)    // right
        }
    }.build()
}
