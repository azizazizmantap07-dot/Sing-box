package com.liquid.box.compose.splash

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projects the soft cube onto the screen.
 *
 * The cube spans [-1, 1] on every axis. Squash / stretch, wobble and lean are all affine, and the
 * camera is orthographic, so the whole cube is one affine map from local space to the screen:
 *
 *     screen = origin + lx * ex + ly * ey + lz * ez
 *
 * That makes everything glued to a face (eyes, mouth, the strap) deform exactly with the body for
 * free: a point on the front face is just `px(u, v, 1)` / `py(u, v, 1)`.
 *
 * The camera looks slightly down at the cube from the front-right, like the app icon: the top, the
 * front (the face with the eyes, +Z) and the right side (+X, where the strap hangs) are visible.
 */
internal class CubeGeometry {
    private var ox = 0f
    private var oy = 0f
    private var exx = 0f
    private var exy = 0f
    private var eyx = 0f
    private var eyy = 0f
    private var ezx = 0f
    private var ezy = 0f

    // Parameters of the current pose, shared by [raw].
    private var k = 1f
    private var fx = 0f
    private var fy = 0f
    private var sx = 1f
    private var sy = 1f
    private var sz = 1f
    private var lean = 0f
    private var cyaw = 1f
    private var syaw = 0f
    private var cr = 1f
    private var sr = 0f

    private var rawX = 0f
    private var rawY = 0f

    /** Screen Y of the floor line the reflection mirrors about. */
    var mirrorY = 0f
        private set

    fun update(sim: SlimeSim) {
        val yawA = BASE_YAW + sim.yaw
        cyaw = cos(yawA)
        syaw = sin(yawA)
        cr = cos(sim.roll)
        sr = sin(sim.roll)
        sy = 1f + sim.a
        val sxz = 1f / sqrt(sy)
        sx = sxz * (1f + sim.b)
        sz = sxz * (1f - sim.b)
        lean = sim.lean
        k = sim.cubePx * 0.5f * sim.zoom
        fx = sim.centerX + sim.x * sim.cubePx
        fy = sim.floorY - sim.lift * sim.cubePx

        raw(0f, 0f, 0f)
        ox = rawX
        oy = rawY
        raw(1f, 0f, 0f)
        exx = rawX - ox
        exy = rawY - oy
        raw(0f, 1f, 0f)
        eyx = rawX - ox
        eyy = rawY - oy
        raw(0f, 0f, 1f)
        ezx = rawX - ox
        ezy = rawY - oy

        // Floor line: the screen Y of the lowest (front-bottom) corner of a resting, undeformed cube.
        val zf = -sin(BASE_YAW) + cos(BASE_YAW)
        mirrorY = sim.floorY + zf * SIN_PITCH * sim.cubePx * 0.5f
    }

    private fun raw(lx: Float, ly: Float, lz: Float) {
        val x = lx * sx + lean * (ly + 1f) * 0.5f
        val y = -1f + (ly + 1f) * sy
        val z = lz * sz
        val x1 = x * cyaw + z * syaw
        val z1 = -x * syaw + z * cyaw
        val y2 = y * COS_PITCH - z1 * SIN_PITCH
        val dx = x1 * k
        val dy = -(y2 + COS_PITCH) * k
        rawX = fx + dx * cr - dy * sr
        rawY = fy + dx * sr + dy * cr
    }

    fun px(lx: Float, ly: Float, lz: Float): Float = ox + lx * exx + ly * eyx + lz * ezx

    fun py(lx: Float, ly: Float, lz: Float): Float = oy + lx * exy + ly * eyy + lz * ezy

    /** Length in pixels of one face unit on the front face (average of both axes). */
    fun faceScale(): Float = 0.5f * (sqrt(exx * exx + exy * exy) + sqrt(eyx * eyx + eyy * eyy))

    /**
     * The six corners of the silhouette, clockwise on screen: top-back-left, top-back-right,
     * bottom-back-right, bottom-front-right, bottom-front-left, top-front-left.
     */
    fun hull(xs: FloatArray, ys: FloatArray) {
        put(xs, ys, 0, -1f, 1f, -1f)
        put(xs, ys, 1, 1f, 1f, -1f)
        put(xs, ys, 2, 1f, -1f, -1f)
        put(xs, ys, 3, 1f, -1f, 1f)
        put(xs, ys, 4, -1f, -1f, 1f)
        put(xs, ys, 5, -1f, 1f, 1f)
    }

    private fun put(xs: FloatArray, ys: FloatArray, i: Int, lx: Float, ly: Float, lz: Float) {
        xs[i] = px(lx, ly, lz)
        ys[i] = py(lx, ly, lz)
    }

    companion object {
        private const val PITCH = 0.47f
        private const val BASE_YAW = -0.63f
        private val SIN_PITCH = sin(PITCH)
        private val COS_PITCH = cos(PITCH)
    }
}
