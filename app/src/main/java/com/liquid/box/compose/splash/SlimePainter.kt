package com.liquid.box.compose.splash

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Colours of the splash: the app icon's violet glass plus the app's own teal / pink accents. */
internal object SplashPalette {
    val Base = Color(0xFF070811)
    val Violet = Color(0xFF7C4DFF)
    val VioletSoft = Color(0xFFA678FF)
    val Lavender = Color(0xFFD9CCFF)
    val RimHi = Color(0xFFF1EAFF)
    val StrapHi = Color(0xFFA487EC)
    val StrapMid = Color(0xFF6C4BBB)
    val StrapLo = Color(0xFF38236C)
    val Teal = Color(0xFF2DD4BF)
    val Pink = Color(0xFFE91E63)
    val EyeCore = Color(0xFFF3FCFF)
    val EyeGlow = Color(0xFF47E3F2)
    val BodyTop = Color(0xFF1C1535)
    val BodyBottom = Color(0xFF09060F)
}

/**
 * Draws the whole splash scene from a [SlimeSim]: backdrop, floor, reflection, the glass slime
 * cube with its face, and the effects. It keeps its scratch paths so a frame allocates (almost)
 * nothing.
 */
internal class SlimePainter {
    private val geo = CubeGeometry()
    private val body = Path()
    private val face = Path()
    private val detail = Path()
    private val hx = FloatArray(6)
    private val hy = FloatArray(6)

    private val dustX = FloatArray(DUST) { hash(it, 1) }
    private val dustY = FloatArray(DUST) { hash(it, 2) }
    private val dustSpeed = FloatArray(DUST) { 0.010f + 0.030f * hash(it, 3) }
    private val dustSize = FloatArray(DUST) { 0.6f + 1.6f * hash(it, 4) }
    private val dustPhase = FloatArray(DUST) { 6.2832f * hash(it, 5) }

    fun paint(scope: DrawScope, sim: SlimeSim) {
        if (sim.width <= 0f || sim.height <= 0f) return
        geo.update(sim)
        with(scope) { paintScene(sim) }
    }

    private fun DrawScope.paintScene(sim: SlimeSim) {
        drawBackdrop(sim)
        drawDust(sim)
        drawFloor(sim)
        drawReflection(sim)
        // The portal punches through everything above; the cube and its effects stay on top.
        if (sim.revealRadius > 1f) {
            punchHole(sim)
            drawPortalRing(sim)
        }
        drawCubeGlow(sim)
        drawCube(sim, 1f, reflect = false)
        drawEffects(sim)
    }

    // ───────────────────────── backdrop ─────────────────────────

    private fun DrawScope.drawBackdrop(sim: SlimeSim) {
        val w = size.width
        val h = size.height
        drawRect(color = SplashPalette.Base)
        val pulse = 0.85f + 0.30f * sim.flash + 0.35f * sim.charge
        val gx = sim.centerX + sim.x * sim.cubePx * 0.4f
        val gy = sim.floorY - sim.cubePx * 0.75f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    SplashPalette.Violet.copy(alpha = 0.36f * pulse),
                    SplashPalette.Violet.copy(alpha = 0.11f * pulse),
                    SplashPalette.Violet.copy(alpha = 0f),
                ),
                center = Offset(gx, gy),
                radius = max(w, h) * 0.62f,
            ),
        )
        val drift = sin(sim.ambient * 0.45f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(SplashPalette.Teal.copy(alpha = 0.13f), SplashPalette.Teal.copy(alpha = 0f)),
                center = Offset(w * (0.10f + 0.03f * drift), h * 0.84f),
                radius = max(w, h) * 0.55f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(SplashPalette.Pink.copy(alpha = 0.09f), SplashPalette.Pink.copy(alpha = 0f)),
                center = Offset(w * (0.92f - 0.03f * drift), h * 0.14f),
                radius = max(w, h) * 0.50f,
            ),
        )
        // Vignette keeps the eye on the cube and darkens the corners like the app background.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = hypot(w, h) * 0.55f,
            ),
        )
    }

    private fun DrawScope.drawDust(sim: SlimeSim) {
        val w = size.width
        val h = size.height
        val unit = sim.cubePx * 0.0055f
        for (i in 0 until DUST) {
            var y = dustY[i] - sim.ambient * dustSpeed[i]
            y -= kotlin.math.floor(y)
            val x = dustX[i] + 0.012f * sin(sim.ambient * 0.6f + dustPhase[i])
            val tw = 0.5f + 0.5f * sin(sim.ambient * 1.7f + dustPhase[i] * 2f)
            val edge = min(y, 1f - y).coerceAtMost(0.15f) / 0.15f
            val c = when (i % 3) {
                0 -> SplashPalette.Lavender
                1 -> SplashPalette.Teal
                else -> Color.White
            }
            val r = unit * dustSize[i]
            drawCircle(color = c.copy(alpha = 0.10f + 0.40f * tw * edge), radius = r, center = Offset(x * w, y * h))
        }
    }

    // ───────────────────────── floor ─────────────────────────

    private fun DrawScope.drawFloor(sim: SlimeSim) {
        val s = sim.cubePx
        val fx = sim.centerX + sim.x * s
        val fy = sim.floorY
        val lift = sim.lift
        val air = 1f / (1f + lift * 0.9f)

        // Violet light spilling under the cube.
        val glow = (0.34f + 0.50f * sim.flash) * air + 0.30f * sim.charge
        withTransform({ scale(scaleX = 1f, scaleY = 0.30f, pivot = Offset(fx, fy)) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        SplashPalette.VioletSoft.copy(alpha = glow.coerceIn(0f, 0.9f)),
                        SplashPalette.Violet.copy(alpha = 0.12f * air),
                        SplashPalette.Violet.copy(alpha = 0f),
                    ),
                    center = Offset(fx, fy),
                    radius = s * 1.55f,
                ),
                radius = s * 1.55f,
                center = Offset(fx, fy),
            )
        }
        // Contact shadow: tighter and darker the closer the cube is to the floor.
        val shadowR = s * 0.78f * (1f + 0.35f * max(0f, -sim.a)) * (0.55f + 0.45f * air)
        withTransform({ scale(scaleX = 1f, scaleY = 0.30f, pivot = Offset(fx, fy)) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.62f * air), Color.Transparent),
                    center = Offset(fx, fy),
                    radius = shadowR,
                ),
                radius = shadowR,
                center = Offset(fx, fy),
            )
        }
        // Ripples from landings.
        for (i in 0 until SlimeSim.MAX_RINGS) {
            val age = sim.ringAge[i]
            if (age < 0f) continue
            val e = 1f - (1f - age) * (1f - age)
            val r = s * (0.55f + 1.7f * e)
            val a = (1f - age).let { it * it } * sim.ringStrength[i].coerceIn(0f, 1f)
            val cx = sim.centerX + sim.ringX[i] * s
            val tl = Offset(cx - r, fy - r * 0.28f)
            val sz = Size(r * 2f, r * 0.56f)
            drawOval(
                color = SplashPalette.Violet.copy(alpha = 0.30f * a),
                topLeft = tl,
                size = sz,
                style = Stroke(width = s * 0.085f * (1f - 0.5f * age)),
            )
            drawOval(
                color = SplashPalette.Lavender.copy(alpha = 0.75f * a),
                topLeft = tl,
                size = sz,
                style = Stroke(width = s * 0.016f),
            )
        }
        // Finale: rings collapse towards the cube while it charges.
        if (sim.charge > 0.01f && sim.finished.not()) {
            for (j in 0..1) {
                val t = ((sim.charge * 1.3f) - j * 0.3f).coerceIn(0f, 1f)
                if (t <= 0f) continue
                val r = s * (2.6f - 1.9f * t)
                val a = t * (1f - 0.4f * t) * 0.6f
                drawOval(
                    color = SplashPalette.Lavender.copy(alpha = a),
                    topLeft = Offset(fx - r, fy - r * 0.28f),
                    size = Size(r * 2f, r * 0.56f),
                    style = Stroke(width = s * 0.014f),
                )
            }
        }
    }

    private fun DrawScope.drawReflection(sim: SlimeSim) {
        if (sim.lift > 3.2f || sim.alpha <= 0.02f) return
        val s = sim.cubePx
        val my = geo.mirrorY
        val air = 1f / (1f + sim.lift * 0.9f)
        val bottom = min(size.height, my + s * 1.35f)
        if (bottom <= my) return
        val strength = 0.46f * air
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, my, size.width, bottom), Paint())
            withTransform({ scale(scaleX = 1f, scaleY = -1f, pivot = Offset(0f, my)) }) {
                drawCube(sim, 1f, reflect = true)
            }
            // Fade the mirror image out with distance from the floor line.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = strength), Color.Transparent),
                    startY = my,
                    endY = bottom,
                ),
                topLeft = Offset(0f, my),
                size = Size(size.width, bottom - my),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }

    // ───────────────────────── portal reveal ─────────────────────────

    private fun DrawScope.punchHole(sim: SlimeSim) {
        val r = sim.revealRadius
        val c = Offset(sim.revealX, sim.revealY)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black,
                0.80f to Color.Black,
                1f to Color.Transparent,
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
            blendMode = BlendMode.DstOut,
        )
    }

    // ───────────────────────── cube ─────────────────────────

    private fun DrawScope.drawCubeGlow(sim: SlimeSim) {
        val al = sim.alpha
        if (al <= 0.02f) return
        val s = sim.cubePx * sim.zoom
        val cx = sim.centerX + sim.x * sim.cubePx
        val cy = sim.floorY - sim.lift * sim.cubePx - SlimeSim.CENTER_RISE * s * (1f + sim.a)
        val strength = (0.20f + 0.22f * sim.flash + 0.25f * sim.charge) * al
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(SplashPalette.Violet.copy(alpha = strength), SplashPalette.Violet.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = s * 1.35f,
            ),
            radius = s * 1.35f,
            center = Offset(cx, cy),
        )
    }

    private fun DrawScope.drawCube(sim: SlimeSim, alphaMul: Float, reflect: Boolean) {
        val al = (sim.alpha * alphaMul).coerceIn(0f, 1f)
        if (al <= 0.01f) return
        val g = geo
        val s = sim.cubePx * sim.zoom
        g.hull(hx, hy)
        var top = hy[0]
        var bottom = hy[0]
        var left = hx[0]
        var right = hx[0]
        for (i in 1 until 6) {
            top = min(top, hy[i])
            bottom = max(bottom, hy[i])
            left = min(left, hx[i])
            right = max(right, hx[i])
        }
        val bulge = max(0f, -sim.a) * 0.10f + abs(sim.b) * 0.06f
        buildRounded(body, hx, hy, 6, s * 0.15f, bulge)

        // Dark glass body.
        drawPath(
            path = body,
            brush = Brush.verticalGradient(
                colors = listOf(SplashPalette.BodyTop, SplashPalette.BodyBottom),
                startY = top,
                endY = bottom,
            ),
            alpha = al,
        )

        clipPath(body) {
            drawFaces(sim, al, s)
            drawHiddenEdges(s, al)
            if (sim.glint >= 0f) drawGlint(sim.glint, left, right, top, bottom, al)
        }

        drawInnerEdges(s, al)
        drawRim(s, al, left, top, right, bottom)
        drawStrap(s, al)
        if (!reflect) drawFace(sim, al)
    }

    private fun DrawScope.drawFaces(sim: SlimeSim, al: Float, s: Float) {
        val g = geo
        // TOP: lighter glass lid with a long diagonal sheen.
        quad(face, g, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, -1f, 1f, 1f)
        val tbx = 0.5f * (g.px(-1f, 1f, -1f) + g.px(1f, 1f, -1f))
        val tby = 0.5f * (g.py(-1f, 1f, -1f) + g.py(1f, 1f, -1f))
        val tfx = 0.5f * (g.px(-1f, 1f, 1f) + g.px(1f, 1f, 1f))
        val tfy = 0.5f * (g.py(-1f, 1f, 1f) + g.py(1f, 1f, 1f))
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF4A3B7C), Color(0xFF1D1638)),
                start = Offset(tbx, tby),
                end = Offset(tfx, tfy),
            ),
            alpha = 0.92f * al,
        )
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.0f), Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.02f)),
                start = Offset(g.px(-1f, 1f, 1f), g.py(-1f, 1f, 1f)),
                end = Offset(g.px(1f, 1f, -1f), g.py(1f, 1f, -1f)),
            ),
            alpha = al,
        )

        // FRONT: nearly black glass with violet light pooling at the bottom edge.
        quad(face, g, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f, -1f, -1f, 1f)
        val fTopX = 0.5f * (g.px(-1f, 1f, 1f) + g.px(1f, 1f, 1f))
        val fTopY = 0.5f * (g.py(-1f, 1f, 1f) + g.py(1f, 1f, 1f))
        val fBotX = 0.5f * (g.px(-1f, -1f, 1f) + g.px(1f, -1f, 1f))
        val fBotY = 0.5f * (g.py(-1f, -1f, 1f) + g.py(1f, -1f, 1f))
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF191236), Color(0xFF0A0719)),
                start = Offset(fTopX, fTopY),
                end = Offset(fBotX, fBotY),
            ),
            alpha = 0.96f * al,
        )
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.Violet.copy(alpha = 0.38f), SplashPalette.Violet.copy(alpha = 0f)),
                start = Offset(fBotX, fBotY),
                end = Offset(fBotX + (fTopX - fBotX) * 0.5f, fBotY + (fTopY - fBotY) * 0.5f),
            ),
            alpha = al,
        )
        val lx0 = g.px(-1f, 0f, 1f)
        val ly0 = g.py(-1f, 0f, 1f)
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0f)),
                start = Offset(lx0, ly0),
                end = Offset(lx0 + (g.px(1f, 0f, 1f) - lx0) * 0.30f, ly0 + (g.py(1f, 0f, 1f) - ly0) * 0.30f),
            ),
            alpha = al,
        )

        // RIGHT: darkest face, violet glow along its far edge and bottom.
        quad(face, g, 1f, 1f, 1f, 1f, 1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f)
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF130D28), Color(0xFF06040D)),
                start = Offset(g.px(1f, 1f, 0f), g.py(1f, 1f, 0f)),
                end = Offset(g.px(1f, -1f, 0f), g.py(1f, -1f, 0f)),
            ),
            alpha = 0.97f * al,
        )
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.Violet.copy(alpha = 0.30f), SplashPalette.Violet.copy(alpha = 0f)),
                start = Offset(g.px(1f, 0f, -1f), g.py(1f, 0f, -1f)),
                end = Offset(g.px(1f, 0f, -0.25f), g.py(1f, 0f, -0.25f)),
            ),
            alpha = al,
        )
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.Violet.copy(alpha = 0.22f), SplashPalette.Violet.copy(alpha = 0f)),
                start = Offset(g.px(1f, -1f, 0f), g.py(1f, -1f, 0f)),
                end = Offset(g.px(1f, -0.3f, 0f), g.py(1f, -0.3f, 0f)),
            ),
            alpha = al,
        )
    }

    /** The back edges seen through the glass, like in the app icon. */
    private fun DrawScope.drawHiddenEdges(s: Float, al: Float) {
        val g = geo
        val hxp = g.px(-1f, -1f, -1f)
        val hyp = g.py(-1f, -1f, -1f)
        val w = s * 0.010f
        val c = SplashPalette.VioletSoft.copy(alpha = 0.20f * al)
        drawLine(color = c, start = Offset(hxp, hyp), end = Offset(g.px(1f, -1f, -1f), g.py(1f, -1f, -1f)), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(hxp, hyp), end = Offset(g.px(-1f, 1f, -1f), g.py(-1f, 1f, -1f)), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(hxp, hyp), end = Offset(g.px(-1f, -1f, 1f), g.py(-1f, -1f, 1f)), strokeWidth = w, cap = StrokeCap.Round)
    }

    private fun DrawScope.drawGlint(t: Float, left: Float, right: Float, top: Float, bottom: Float, al: Float) {
        val bw = (right - left) * 0.34f
        val c = left - bw + (right - left + 2f * bw) * t
        val fade = sin(t * 3.1416f).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.40f * fade * al), Color.White.copy(alpha = 0f)),
                start = Offset(c - bw, top),
                end = Offset(c + bw, top + (bottom - top) * 0.28f),
            ),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
        )
    }

    private fun DrawScope.drawInnerEdges(s: Float, al: Float) {
        val g = geo
        // front / right vertical edge: the brightest line of the cube.
        edgeLine(g.px(1f, 0.86f, 1f), g.py(1f, 0.86f, 1f), g.px(1f, -0.86f, 1f), g.py(1f, -0.86f, 1f), s, al, 1f)
        // top / front edge.
        edgeLine(g.px(-0.86f, 1f, 1f), g.py(-0.86f, 1f, 1f), g.px(0.86f, 1f, 1f), g.py(0.86f, 1f, 1f), s, al, 0.85f)
        // top / right edge.
        edgeLine(g.px(1f, 1f, 0.86f), g.py(1f, 1f, 0.86f), g.px(1f, 1f, -0.86f), g.py(1f, 1f, -0.86f), s, al, 0.6f)
        // Hot specular spot where the three faces meet.
        val nx = g.px(1f, 1f, 1f)
        val ny = g.py(1f, 1f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.75f * al), Color.White.copy(alpha = 0f)),
                center = Offset(nx, ny),
                radius = s * 0.085f,
            ),
            radius = s * 0.085f,
            center = Offset(nx, ny),
        )
    }

    private fun DrawScope.edgeLine(x0: Float, y0: Float, x1: Float, y1: Float, s: Float, al: Float, k: Float) {
        val a = Offset(x0, y0)
        val b = Offset(x1, y1)
        drawLine(color = SplashPalette.Violet.copy(alpha = 0.16f * k * al), start = a, end = b, strokeWidth = s * 0.050f, cap = StrokeCap.Round)
        drawLine(color = SplashPalette.VioletSoft.copy(alpha = 0.34f * k * al), start = a, end = b, strokeWidth = s * 0.022f, cap = StrokeCap.Round)
        drawLine(color = SplashPalette.RimHi.copy(alpha = 0.80f * k * al), start = a, end = b, strokeWidth = s * 0.008f, cap = StrokeCap.Round)
    }

    private fun DrawScope.drawRim(s: Float, al: Float, left: Float, top: Float, right: Float, bottom: Float) {
        val round = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = body, color = SplashPalette.Violet.copy(alpha = 0.11f * al), style = round)
        drawPath(
            path = body,
            color = SplashPalette.VioletSoft.copy(alpha = 0.26f * al),
            style = Stroke(width = s * 0.036f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = body,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.RimHi, SplashPalette.VioletSoft, Color(0xFF6B3FE0)),
                start = Offset(left, top),
                end = Offset(right, bottom),
            ),
            alpha = 0.96f * al,
            style = Stroke(width = s * 0.014f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** The satin strap: over the lid, down the right side, ending in a rounded tab. */
    private fun DrawScope.drawStrap(s: Float, al: Float) {
        val g = geo
        val zc = -0.06f
        val zw = 0.27f
        val lift = 1.05f
        // lid part
        quad(face, g, -lift, lift, zc - zw, lift, lift, zc - zw, lift, lift, zc + zw, -lift, lift, zc + zw)
        drawPath(
            path = face,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.StrapLo, SplashPalette.StrapHi, SplashPalette.StrapMid),
                start = Offset(g.px(0f, lift, zc - zw), g.py(0f, lift, zc - zw)),
                end = Offset(g.px(0f, lift, zc + zw), g.py(0f, lift, zc + zw)),
            ),
            alpha = 0.98f * al,
        )
        // satin highlight along the strap
        drawLine(
            color = Color.White.copy(alpha = 0.34f * al),
            start = Offset(g.px(-lift, lift, zc + zw * 0.55f), g.py(-lift, lift, zc + zw * 0.55f)),
            end = Offset(g.px(lift, lift, zc + zw * 0.55f), g.py(lift, lift, zc + zw * 0.55f)),
            strokeWidth = s * 0.012f,
            cap = StrokeCap.Round,
        )
        // tab on the right side
        val yEnd = 0.18f
        val r = 0.11f
        val z0 = zc - zw
        val z1 = zc + zw
        detail.reset()
        detail.moveTo(g.px(lift, lift, z0), g.py(lift, lift, z0))
        detail.lineTo(g.px(lift, lift, z1), g.py(lift, lift, z1))
        detail.lineTo(g.px(lift, yEnd + r, z1), g.py(lift, yEnd + r, z1))
        detail.cubicTo(
            g.px(lift, yEnd + r * 0.45f, z1), g.py(lift, yEnd + r * 0.45f, z1),
            g.px(lift, yEnd, z1 - r * 0.45f), g.py(lift, yEnd, z1 - r * 0.45f),
            g.px(lift, yEnd, z1 - r), g.py(lift, yEnd, z1 - r),
        )
        detail.lineTo(g.px(lift, yEnd, z0 + r), g.py(lift, yEnd, z0 + r))
        detail.cubicTo(
            g.px(lift, yEnd, z0 + r * 0.45f), g.py(lift, yEnd, z0 + r * 0.45f),
            g.px(lift, yEnd + r * 0.45f, z0), g.py(lift, yEnd + r * 0.45f, z0),
            g.px(lift, yEnd + r, z0), g.py(lift, yEnd + r, z0),
        )
        detail.close()
        drawPath(
            path = detail,
            brush = Brush.linearGradient(
                colors = listOf(SplashPalette.StrapMid, SplashPalette.StrapLo),
                start = Offset(g.px(lift, lift, 0f), g.py(lift, lift, 0f)),
                end = Offset(g.px(lift, yEnd, 0f), g.py(lift, yEnd, 0f)),
            ),
            alpha = 0.98f * al,
        )
        drawPath(
            path = detail,
            color = SplashPalette.StrapHi.copy(alpha = 0.55f * al),
            style = Stroke(width = s * 0.007f, join = StrokeJoin.Round),
        )
    }

    // ───────────────────────── face ─────────────────────────

    private fun DrawScope.drawFace(sim: SlimeSim, al: Float) {
        val g = geo
        val fs = g.faceScale()
        val lookU = sim.lookX * 0.11f
        val lookV = sim.lookY * 0.06f
        val baseV = 0.10f
        val open = (1f - sim.happy * 1.6f).coerceIn(0f, 1f)
        val arcs = (sim.happy * 1.6f).coerceIn(0f, 1f)
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            val cu = sgn * 0.31f + lookU
            val cv = baseV + lookV
            val sizeK = 1f + 0.07f * sim.lookX * sgn
            val hw = 0.126f * sizeK * (1f + 0.10f * sim.surprise)
            val hh = (0.232f * sizeK * sim.eyeOpen).coerceAtLeast(0.020f)
            val ex = g.px(cu, cv, 1f)
            val ey = g.py(cu, cv, 1f)
            val glowR = fs * 0.50f

            if (open > 0.02f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(SplashPalette.EyeGlow.copy(alpha = 0.50f * open * al), SplashPalette.EyeGlow.copy(alpha = 0f)),
                        center = Offset(ex, ey),
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = Offset(ex, ey),
                )
                faceRRect(face, g, cu, cv, hw, hh, min(hw, hh) * 0.98f)
                drawPath(
                    path = face,
                    brush = Brush.radialGradient(
                        colors = listOf(SplashPalette.EyeCore, SplashPalette.EyeCore, SplashPalette.EyeGlow),
                        center = Offset(ex, ey),
                        radius = fs * max(hw, hh) * 1.15f,
                    ),
                    alpha = open * al,
                )
                // Specular dot.
                val hu = cu - hw * 0.30f
                val hv = cv + hh * 0.42f
                drawCircle(
                    color = Color.White.copy(alpha = 0.95f * open * al * min(1f, sim.eyeOpen * 1.6f)),
                    radius = fs * 0.030f,
                    center = Offset(g.px(hu, hv, 1f), g.py(hu, hv, 1f)),
                )
            }
            if (arcs > 0.02f) {
                // Happy "^" eye.
                detail.reset()
                detail.moveTo(g.px(cu - hw * 1.1f, cv - 0.04f, 1f), g.py(cu - hw * 1.1f, cv - 0.04f, 1f))
                detail.cubicTo(
                    g.px(cu - hw * 0.6f, cv + 0.17f, 1f), g.py(cu - hw * 0.6f, cv + 0.17f, 1f),
                    g.px(cu + hw * 0.6f, cv + 0.17f, 1f), g.py(cu + hw * 0.6f, cv + 0.17f, 1f),
                    g.px(cu + hw * 1.1f, cv - 0.04f, 1f), g.py(cu + hw * 1.1f, cv - 0.04f, 1f),
                )
                drawPath(
                    path = detail,
                    color = SplashPalette.EyeGlow.copy(alpha = 0.45f * arcs * al),
                    style = Stroke(width = fs * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = detail,
                    color = SplashPalette.EyeCore.copy(alpha = arcs * al),
                    style = Stroke(width = fs * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // Mouth: a tiny smile that opens into an "o" when the slime is surprised or flying up.
        val mu = lookU * 0.6f
        val mv = -0.30f + lookV * 0.5f
        val smileA = (1f - sim.mouth) * al
        if (smileA > 0.02f) {
            detail.reset()
            detail.moveTo(g.px(mu - 0.10f, mv + 0.03f, 1f), g.py(mu - 0.10f, mv + 0.03f, 1f))
            detail.cubicTo(
                g.px(mu - 0.05f, mv - 0.06f, 1f), g.py(mu - 0.05f, mv - 0.06f, 1f),
                g.px(mu + 0.05f, mv - 0.06f, 1f), g.py(mu + 0.05f, mv - 0.06f, 1f),
                g.px(mu + 0.10f, mv + 0.03f, 1f), g.py(mu + 0.10f, mv + 0.03f, 1f),
            )
            drawPath(
                path = detail,
                color = SplashPalette.EyeGlow.copy(alpha = 0.30f * smileA),
                style = Stroke(width = fs * 0.085f, cap = StrokeCap.Round),
            )
            drawPath(
                path = detail,
                color = SplashPalette.EyeCore.copy(alpha = 0.90f * smileA),
                style = Stroke(width = fs * 0.032f, cap = StrokeCap.Round),
            )
        }
        if (sim.mouth > 0.05f) {
            val mh = 0.095f * sim.mouth
            faceRRect(face, g, mu, mv, 0.065f, mh, min(0.065f, mh))
            drawPath(path = face, color = SplashPalette.EyeCore.copy(alpha = 0.92f * sim.mouth * al))
        }
    }

    // ───────────────────────── effects ─────────────────────────

    private fun DrawScope.drawEffects(sim: SlimeSim) {
        val s = sim.cubePx
        for (i in 0 until SlimeSim.MAX_DROPS) {
            val a = sim.dropAlpha(i)
            if (a <= 0.01f) continue
            val c = Offset(sim.centerX + sim.dropX[i] * s, sim.floorY - sim.dropY[i] * s)
            val r = sim.dropSize[i] * s
            drawCircle(color = SplashPalette.VioletSoft.copy(alpha = 0.16f * a), radius = r * 2.1f, center = c)
            drawCircle(color = Color.White.copy(alpha = 0.92f * a), radius = r, center = c)
        }
    }

    /** Glowing lip of the portal that opens the app. Drawn behind the cube. */
    private fun DrawScope.drawPortalRing(sim: SlimeSim) {
        if (sim.revealProgress >= 1f) return
        val s = sim.cubePx
        val p = sim.revealProgress
        val c = Offset(sim.revealX, sim.revealY)
        val r = sim.revealRadius * 0.90f
        val fade = (1f - p * p).coerceIn(0f, 1f)
        drawCircle(
            color = SplashPalette.Violet.copy(alpha = 0.30f * fade),
            radius = r,
            center = c,
            style = Stroke(width = s * 0.16f),
        )
        drawCircle(
            color = SplashPalette.Lavender.copy(alpha = 0.85f * fade),
            radius = r,
            center = c,
            style = Stroke(width = s * 0.018f),
        )
    }

    // ───────────────────────── helpers ─────────────────────────

    /** Fills [path] with the quad given by four local-space corners. */
    private fun quad(
        path: Path,
        g: CubeGeometry,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
    ) {
        path.reset()
        path.moveTo(g.px(ax, ay, az), g.py(ax, ay, az))
        path.lineTo(g.px(bx, by, bz), g.py(bx, by, bz))
        path.lineTo(g.px(cx, cy, cz), g.py(cx, cy, cz))
        path.lineTo(g.px(dx, dy, dz), g.py(dx, dy, dz))
        path.close()
    }

    /** Rounded rectangle drawn on the front face in face coordinates (u, v in [-1, 1]). */
    private fun faceRRect(path: Path, g: CubeGeometry, cu: Float, cv: Float, hw: Float, hh: Float, r: Float) {
        val x0 = cu - hw
        val x1 = cu + hw
        val y0 = cv - hh
        val y1 = cv + hh
        val q = r * 0.5523f
        path.reset()
        path.moveTo(g.px(x0 + r, y1, 1f), g.py(x0 + r, y1, 1f))
        path.lineTo(g.px(x1 - r, y1, 1f), g.py(x1 - r, y1, 1f))
        path.cubicTo(
            g.px(x1 - r + q, y1, 1f), g.py(x1 - r + q, y1, 1f),
            g.px(x1, y1 - r + q, 1f), g.py(x1, y1 - r + q, 1f),
            g.px(x1, y1 - r, 1f), g.py(x1, y1 - r, 1f),
        )
        path.lineTo(g.px(x1, y0 + r, 1f), g.py(x1, y0 + r, 1f))
        path.cubicTo(
            g.px(x1, y0 + r - q, 1f), g.py(x1, y0 + r - q, 1f),
            g.px(x1 - r + q, y0, 1f), g.py(x1 - r + q, y0, 1f),
            g.px(x1 - r, y0, 1f), g.py(x1 - r, y0, 1f),
        )
        path.lineTo(g.px(x0 + r, y0, 1f), g.py(x0 + r, y0, 1f))
        path.cubicTo(
            g.px(x0 + r - q, y0, 1f), g.py(x0 + r - q, y0, 1f),
            g.px(x0, y0 + r - q, 1f), g.py(x0, y0 + r - q, 1f),
            g.px(x0, y0 + r, 1f), g.py(x0, y0 + r, 1f),
        )
        path.lineTo(g.px(x0, y1 - r, 1f), g.py(x0, y1 - r, 1f))
        path.cubicTo(
            g.px(x0, y1 - r + q, 1f), g.py(x0, y1 - r + q, 1f),
            g.px(x0 + r - q, y1, 1f), g.py(x0 + r - q, y1, 1f),
            g.px(x0 + r, y1, 1f), g.py(x0 + r, y1, 1f),
        )
        path.close()
    }

    /**
     * Rounded polygon through [n] points (clockwise). [bulge] curves every edge outwards a little,
     * which makes the squashed cube look soft instead of rigid.
     */
    private fun buildRounded(path: Path, xs: FloatArray, ys: FloatArray, n: Int, radius: Float, bulge: Float) {
        path.reset()
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += xs[i]
            cy += ys[i]
        }
        cx /= n
        cy /= n
        var firstAx = 0f
        var firstAy = 0f
        var prevBx = 0f
        var prevBy = 0f
        for (i in 0 until n) {
            val p = (i + n - 1) % n
            val q = (i + 1) % n
            val dxp = xs[p] - xs[i]
            val dyp = ys[p] - ys[i]
            val dxq = xs[q] - xs[i]
            val dyq = ys[q] - ys[i]
            val lp = max(hypot(dxp, dyp), 1e-3f)
            val lq = max(hypot(dxq, dyq), 1e-3f)
            val t = min(radius, min(lp, lq) * 0.45f)
            val ax = xs[i] + dxp / lp * t
            val ay = ys[i] + dyp / lp * t
            val bx = xs[i] + dxq / lq * t
            val by = ys[i] + dyq / lq * t
            if (i == 0) {
                path.moveTo(ax, ay)
                firstAx = ax
                firstAy = ay
            } else {
                edgeTo(path, prevBx, prevBy, ax, ay, cx, cy, bulge)
            }
            path.cubicTo(
                ax + (xs[i] - ax) * KAPPA, ay + (ys[i] - ay) * KAPPA,
                bx + (xs[i] - bx) * KAPPA, by + (ys[i] - by) * KAPPA,
                bx, by,
            )
            prevBx = bx
            prevBy = by
        }
        edgeTo(path, prevBx, prevBy, firstAx, firstAy, cx, cy, bulge)
        path.close()
    }

    private fun edgeTo(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, cx: Float, cy: Float, bulge: Float) {
        if (bulge <= 0.001f) {
            path.lineTo(x1, y1)
            return
        }
        val dx = x1 - x0
        val dy = y1 - y0
        val len = max(hypot(dx, dy), 1e-3f)
        var nx = -dy / len
        var ny = dx / len
        if (nx * ((x0 + x1) * 0.5f - cx) + ny * ((y0 + y1) * 0.5f - cy) < 0f) {
            nx = -nx
            ny = -ny
        }
        val off = bulge * len
        path.cubicTo(
            x0 + dx / 3f + nx * off, y0 + dy / 3f + ny * off,
            x0 + dx * 2f / 3f + nx * off, y0 + dy * 2f / 3f + ny * off,
            x1, y1,
        )
    }

    private companion object {
        const val DUST = 34
        const val KAPPA = 0.5523f

        /** Deterministic pseudo random 0..1 per (index, channel). */
        fun hash(i: Int, channel: Int): Float {
            var h = i * 374761393 + channel * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 65536f
        }
    }
}
