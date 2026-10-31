package com.liquid.box.compose.splash

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Soft-body "slime cube" simulation behind the launch splash.
 *
 * Pure Kotlin on purpose (no Android, no Compose): it only produces numbers, [SlimeCubeRenderer]
 * turns them into pixels. Everything is expressed in *cube units* (1 unit = one cube edge) and
 * seconds, so the motion looks the same on every screen size and density.
 *
 * How it feels alive:
 *  - the body is a volume-preserving spring: it stretches while it flies, squashes when it lands
 *    and then jiggles ([a] = vertical stretch, [b] = side-to-side wobble, [lean] = top shear);
 *  - a small script (drop in, two hops, big finale leap) drives the choreography, the user can poke
 *    it, charge-jump it or call it to a spot at any time;
 *  - the face (blink, look-at-finger, happy / surprised / determined) reacts to what the body does.
 *
 * The simulation runs at a fixed 240 Hz sub-step, so a dropped frame never changes the motion.
 */
class SlimeSim(private val speed: Float = 1f) {

    // ───────────────────────── layout (pixels) ─────────────────────────
    var width = 0f
        private set
    var height = 0f
        private set

    /** Edge length of the cube in pixels. */
    var cubePx = 1f
        private set
    var centerX = 0f
        private set

    /** Screen Y of the footprint centre while the cube rests on the floor. */
    var floorY = 0f
        private set
    private var maxHop = 1f
    private var offscreenLift = 4f

    // ───────────────────────── body pose ─────────────────────────
    /** Lateral offset of the cube from the screen centre, in units. */
    var x = 0f
        private set

    /** Height of the cube's base above the floor, in units. */
    var lift = 0f
        private set

    /** Vertical stretch (> 0) / squash (< 0). Volume preserving. */
    var a = 0f
        private set

    /** X / Z wobble: > 0 wider on X and thinner on Z. */
    var b = 0f
        private set

    /** Shear of the top face relative to the base, in half-edge units. */
    var lean = 0f
        private set

    /** Tilt in the screen plane, radians. */
    var roll = 0f
        private set

    /** Yaw offset added to the renderer's base yaw, radians. */
    var yaw = 0f
        private set

    /** Extra uniform scale (used by the finale leap towards the camera). */
    var zoom = 1f
        private set
    var alpha = 1f
        private set

    // ───────────────────────── face ─────────────────────────
    var lookX = 0f
        private set
    var lookY = 0f
        private set

    /** 0 = eyes closed, 1 = open, > 1 = wide. Already includes blink / squint / mood. */
    var eyeOpen = 1f
        private set
    var happy = 0f
        private set
    var surprise = 0f
        private set
    var determined = 0f
        private set

    /** 0 = small smile, 1 = wide open "o". */
    var mouth = 0f
        private set

    // ───────────────────────── effects ─────────────────────────
    var flash = 0f
        private set

    /** 0..1 while the finale crouch charges up. */
    var charge = 0f
        private set

    /** Glass glint sweep across the cube, < 0 = idle, 0..1 = running. */
    var glint = -1f
        private set

    /** Wordmark reveal 0..1 and its fade-out (1 = fully visible). */
    var wordmark = 0f
        private set
    var wordmarkFade = 1f
        private set

    /** Free running clock for ambient motion (glows, dust). */
    var ambient = 0f
        private set

    /** Portal reveal: radius in px (0 = closed), its centre, and 0..1 progress. */
    var revealRadius = 0f
        private set
    var revealX = 0f
        private set
    var revealY = 0f
        private set
    var revealProgress = 0f
        private set

    /** True once the splash has fully revealed the app and can be removed. */
    var finished = false
        private set

    /** Set by the host once the real UI exists beneath the splash. */
    var ready = false

    val ringX = FloatArray(MAX_RINGS)
    val ringAge = FloatArray(MAX_RINGS) { -1f }
    val ringStrength = FloatArray(MAX_RINGS)

    val dropX = FloatArray(MAX_DROPS)
    val dropY = FloatArray(MAX_DROPS)
    private val dropVX = FloatArray(MAX_DROPS)
    private val dropVY = FloatArray(MAX_DROPS)
    val dropLife = FloatArray(MAX_DROPS)
    private val dropLifeMax = FloatArray(MAX_DROPS)
    val dropSize = FloatArray(MAX_DROPS)

    /** Pointer in pixels, used for the eyes. */
    var pointerActive = false
        private set
    private var pointerX = 0f
    private var pointerY = 0f

    /** Strongest haptic request since the host last asked: 0 none, 1 light, 2 medium, 3 heavy. */
    private var haptic = 0

    // ───────────────────────── internals ─────────────────────────
    private var time = 0f
    private var vx = 0f
    private var vLift = 0f
    private var airborne = false
    private var gravity = G_HOP
    private var landed = false
    private var wobbleSign = 1f

    private var av = 0f
    private var bv = 0f
    private var leanV = 0f
    private var rollV = 0f
    private var yawV = 0f

    private var scriptCrouch = 0f
    private var crouching = false
    private var userHold = false
    private var holdT = 0f
    private var userCooldown = 0f
    private var bufferT = 0f
    private var bufferX = 0f

    private var cmd: Cmd? = null
    private var cmdT = 0f
    private var phase = 0
    private var recoverT = 0f
    private val queue = ArrayDeque<Cmd>()
    private var skipRequested = false

    private var finPhase = 0
    private var finT = 0f
    private var leapFrom = 0f
    private var leapRise = 0f
    private var leapFromX = 0f
    private var finaleRevealX = 0f
    private var finaleRevealY = 0f
    private var startedAt = -1f

    private var blinkIn = 1.0f
    private var blinkT = -1f
    private var gazeIn = 1.4f
    private var gazeX = 0f
    private var gazeY = 0f
    private var seed = 0x2F6E2B1

    private enum class Kind { DROP, HOP, WAIT }

    private class Cmd(val kind: Kind, val p0: Float = 0f, val p1: Float = 0f, val p2: Float = 0f, val p3: Float = 0f)

    init {
        reset()
    }

    /** Restarts the whole choreography. */
    fun reset() {
        time = 0f
        x = 0f
        lift = 40f
        vx = 0f
        vLift = 0f
        a = 0f
        b = 0f
        lean = 0f
        roll = 0f
        yaw = 0f
        av = 0f
        bv = 0f
        leanV = 0f
        rollV = 0f
        yawV = 0f
        zoom = 1f
        alpha = 0f
        airborne = false
        crouching = false
        scriptCrouch = 0f
        finPhase = 0
        finished = false
        skipRequested = false
        cmd = null
        queue.clear()
        queue.addLast(Cmd(Kind.DROP))
        queue.addLast(Cmd(Kind.HOP, 0.46f, 0f, 0.10f, 0.10f))
        queue.addLast(Cmd(Kind.HOP, 0.82f, 0.34f, 0.13f, 0.22f))
        wordmark = 0f
        wordmarkFade = 1f
        revealRadius = 0f
        revealProgress = 0f
        startedAt = -1f
        for (i in 0 until MAX_RINGS) ringAge[i] = -1f
        for (i in 0 until MAX_DROPS) dropLife[i] = 0f
    }

    fun setViewport(w: Float, hgt: Float) {
        if (w == width && hgt == height) return
        width = w
        height = hgt
        cubePx = cubeSize(w, hgt)
        centerX = w * 0.5f
        floorY = floorLine(w, hgt)
        val silhouette = cubePx * SILHOUETTE_H
        maxHop = ((floorY - hgt * 0.06f - silhouette) / cubePx).coerceIn(0.30f, 1.30f)
        offscreenLift = (floorY + silhouette) / cubePx + 0.25f
    }

    // ───────────────────────── input ─────────────────────────

    fun touchDown(px: Float, py: Float) {
        pointerActive = true
        pointerX = px
        pointerY = py
        if (finPhase != 0 || finished || startedAt < 0f) return
        if (!hitsCube(px, py)) return
        if (canUserHop()) {
            // Press and hold squashes the slime under the finger; letting go launches it.
            takeOverFromScript()
            userHold = true
            holdT = 0f
        } else {
            // Busy hopping: a poke in mid-air just makes it wobble.
            boop()
        }
    }

    fun touchMove(px: Float, py: Float) {
        pointerX = px
        pointerY = py
    }

    /** [wasTap] is true for a short press that did not travel. */
    fun touchUp(px: Float, py: Float, wasTap: Boolean) {
        pointerX = px
        pointerY = py
        pointerActive = false
        if (finPhase != 0 || finished) {
            userHold = false
            return
        }
        if (userHold) {
            userHold = false
            val charged = (holdT / CHARGE_TIME).coerceIn(0f, 1f)
            val height = (0.36f + 0.80f * charged).coerceAtMost(maxHop)
            userHop(height, 0f, 0.06f)
            surprise = 1f
            queueHaptic(if (charged > 0.5f) 2 else 1)
        } else if (wasTap && !hitsCube(px, py) && finPhase == 0 && time < USER_DEADLINE) {
            // Call the slime over: it hops towards the tapped column. A tap while it is still in
            // the air is remembered for a moment and answered as soon as it lands.
            bufferX = ((px - centerX) / cubePx).coerceIn(-MAX_X, MAX_X)
            bufferT = if (canUserHop() && userCooldown <= 0f) 0.001f else BUFFER_TIME
            spawnRing(bufferX, 0.45f)
        }
    }

    /** Skip request (system Back): finish the show as fast as it can be done gracefully. */
    fun skip() {
        skipRequested = true
        queue.clear()
    }

    /** Returns and clears the strongest pending haptic request. */
    fun takeHaptic(): Int {
        val h = haptic
        haptic = 0
        return h
    }

    private fun queueHaptic(level: Int) {
        if (level > haptic) haptic = level
    }

    private fun hitsCube(px: Float, py: Float): Boolean {
        val cx = centerX + x * cubePx
        val baseY = floorY - lift * cubePx
        val cy = baseY - CENTER_RISE * cubePx * (1f + a) * zoom
        val rx = 0.80f * cubePx * zoom
        val ry = 0.86f * cubePx * (1f + a).coerceAtLeast(0.5f) * zoom
        val nx = (px - cx) / rx
        val ny = (py - cy) / ry
        return nx * nx + ny * ny <= 1f
    }

    private fun canUserHop(): Boolean =
        !airborne && finPhase == 0 && !finished && startedAt >= 0f && time < USER_DEADLINE

    /** Drops the script's current step so the user's action can start now; a pending crouch is kept. */
    private fun takeOverFromScript() {
        val c = cmd
        if (c != null && c.kind == Kind.HOP && phase == 0) queue.addFirst(c)
        cmd = null
        crouching = false
        scriptCrouch = 0f
    }

    private fun userHop(height: Float, dx: Float, crouchDur: Float) {
        takeOverFromScript()
        queue.addFirst(Cmd(Kind.HOP, height, dx, crouchDur, 0.16f))
        userCooldown = 0.25f
    }

    private fun boop() {
        av -= 7f
        bv += 2.5f * wobbleSign
        wobbleSign = -wobbleSign
        surprise = 1f
        queueHaptic(1)
    }

    // ───────────────────────── stepping ─────────────────────────

    fun step(realDt: Float) {
        var remaining = (realDt * speed).coerceIn(0f, 0.1f)
        val frameDt = remaining
        while (remaining > 1e-6f) {
            val dt = min(remaining, SUB_STEP)
            remaining -= dt
            time += dt
            runScript(dt)
            integrate(dt)
        }
        frameUpdate(frameDt)
    }

    // ───────────────────────── script ─────────────────────────

    private fun runScript(dt: Float) {
        if (userHold) {
            holdT += dt
        }
        if (userCooldown > 0f) userCooldown -= dt
        if (bufferT > 0f) {
            bufferT -= dt
            if (canUserHop() && userCooldown <= 0f && !userHold) {
                bufferT = 0f
                userHop(0.5f, (bufferX - x).coerceIn(-0.7f, 0.7f), 0.08f)
            }
        }
        if (finPhase != 0) {
            runFinale(dt)
            return
        }
        val c = cmd
        if (c == null) {
            startNext()
            return
        }
        cmdT += dt
        when (c.kind) {
            Kind.DROP -> runDrop()
            Kind.HOP -> runHop(c, dt)
            Kind.WAIT -> if (cmdT >= c.p0) cmd = null
        }
    }

    private fun startNext() {
        if (userHold) return
        if (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            cmd = next
            cmdT = 0f
            phase = 0
            recoverT = 0f
            when (next.kind) {
                Kind.DROP -> startDrop()
                Kind.HOP -> startHop(next)
                Kind.WAIT -> Unit
            }
            return
        }
        if (airborne || startedAt < 0f) return
        if (ready) {
            beginFinale()
        } else if (!skipRequested) {
            // The app is still loading: keep the slime busy with small idle hops.
            queue.addLast(Cmd(Kind.WAIT, 0.28f))
            queue.addLast(Cmd(Kind.HOP, 0.30f, 0f, 0.09f, 0.12f))
        }
    }

    private fun startDrop() {
        lift = offscreenLift
        vLift = -2.5f
        airborne = true
        gravity = G_DROP
        alpha = 1f
        av = 4f
    }

    private fun runDrop() {
        if (phase == 0) {
            if (landed) {
                landed = false
                phase = 1
                startedAt = time
            }
        } else if (time - startedAt >= DROP_RECOVER) {
            cmd = null
        }
    }

    private fun startHop(c: Cmd) {
        crouching = true
        scriptCrouch = -(0.14f + 0.16f * c.p0).coerceAtMost(0.30f)
        landed = false
    }

    private fun runHop(c: Cmd, dt: Float) {
        when (phase) {
            0 -> if (cmdT >= c.p2) {
                launch(c.p0.coerceAtMost(maxHop), c.p1)
                crouching = false
                scriptCrouch = 0f
                phase = 1
            }
            1 -> if (landed) {
                landed = false
                phase = 2
                recoverT = 0f
            }
            else -> {
                recoverT += dt
                if (recoverT >= c.p3) cmd = null
            }
        }
    }

    private fun launch(height: Float, dx: Float) {
        val v0 = sqrt(2f * G_HOP * height)
        gravity = G_HOP
        vLift = v0
        lift = 0.002f
        airborne = true
        val airTime = 2f * v0 / G_HOP
        vx = dx / airTime
        av += 8.0f
        leanV += if (dx > 0.01f) -2.2f else if (dx < -0.01f) 2.2f else 0f
        yawV += dx * 1.4f
        surprise = max(surprise, 0.55f)
        flash = max(flash, 0.12f)
    }

    private fun beginFinale() {
        finPhase = 1
        finT = 0f
        crouching = true
        scriptCrouch = -0.40f
        queueHaptic(1)
    }

    private fun runFinale(dt: Float) {
        finT += dt
        if (finPhase == 1) {
            charge = (finT / FINALE_CROUCH).coerceIn(0f, 1f)
            determined = max(determined, charge)
            if (finT >= FINALE_CROUCH) {
                finPhase = 2
                finT = 0f
                crouching = false
                scriptCrouch = 0f
                airborne = false
                leapFrom = lift
                leapFromX = x
                leapRise = (floorY / cubePx) * 0.92f
                av += 9.0f
                flash = 1f
                queueHaptic(3)
                finaleRevealX = centerX + x * cubePx
                finaleRevealY = floorY - lift * cubePx - CENTER_RISE * cubePx
                revealX = finaleRevealX
                revealY = finaleRevealY
            }
            return
        }
        // Leap towards the camera: kinematic so the portal timing is exact.
        val u = (finT / FINALE_LEAP).coerceIn(0f, 1f)
        val rise = 1f - (1f - u) * (1f - u)
        lift = leapFrom + leapRise * rise
        x = leapFromX * (1f - rise)
        zoom = 1f + 1.5f * u * u
        alpha = 1f - smooth((u - 0.30f) / 0.45f)
        happy = max(happy, 0.8f)
        wordmarkFade = 1f - smooth(u / 0.5f)
        val p = ((u - 0.06f) / 0.94f).coerceIn(0f, 1f)
        revealProgress = p
        revealY = finaleRevealY - leapRise * cubePx * 0.22f * rise
        val maxR = hypot(width, height) * 1.05f
        revealRadius = maxR * smooth(p)
        if (u >= 1f) finished = true
    }

    // ───────────────────────── physics ─────────────────────────

    private fun integrate(dt: Float) {
        val leaping = finPhase == 2
        if (airborne && !leaping) {
            vLift -= gravity * dt
            lift += vLift * dt
            x += vx * dt
            if (lift <= 0f && vLift < 0f) land()
        }
        if (!airborne && !leaping && lift != 0f) lift = 0f

        // Vertical squash / stretch spring.
        val breath = if (!airborne && finPhase == 0) 0.012f * sin(time * 2f * PI.toFloat() * 0.7f) else 0f
        val holdDepth = if (userHold) 0.06f + 0.26f * (holdT / CHARGE_TIME).coerceIn(0f, 1f) else 0f
        val target = when {
            leaping -> 0.34f * (1f - (finT / FINALE_LEAP).coerceIn(0f, 1f))
            airborne -> (abs(vLift) * 0.062f).coerceAtMost(0.30f)
            else -> scriptCrouch - holdDepth + breath
        }
        val zeta = if (crouching || userHold) 0.62f else 0.17f
        av += (-W_SQUASH * W_SQUASH * (a - target) - 2f * zeta * W_SQUASH * av) * dt
        a = (a + av * dt).coerceIn(-0.47f, 0.48f)

        bv += (-W_WOBBLE * W_WOBBLE * b - 2f * 0.15f * W_WOBBLE * bv) * dt
        b = (b + bv * dt).coerceIn(-0.22f, 0.22f)

        leanV += (-W_LEAN * W_LEAN * lean - 2f * 0.22f * W_LEAN * leanV) * dt
        lean = (lean + leanV * dt).coerceIn(-0.40f, 0.40f)

        val rollTarget = if (airborne) (vx * 0.10f).coerceIn(-0.14f, 0.14f) else 0f
        rollV += (-W_ROLL * W_ROLL * (roll - rollTarget) - 2f * 0.35f * W_ROLL * rollV) * dt
        roll += rollV * dt

        val yawTarget = lookX * 0.10f
        yawV += (-W_YAW * W_YAW * (yaw - yawTarget) - 2f * 0.45f * W_YAW * yawV) * dt
        yaw += yawV * dt
    }

    private fun land() {
        val impact = -vLift
        val strength = (impact / 8f).coerceIn(0f, 1f)
        lift = 0f
        vLift = 0f
        airborne = false
        landed = true
        av -= 13.0f * strength
        bv += (2.0f + 7f * strength) * wobbleSign
        leanV += vx * 3.2f
        wobbleSign = -wobbleSign
        vx = 0f
        x = x.coerceIn(-MAX_X, MAX_X)
        if (strength > 0.22f) {
            spawnRing(x, strength)
            flash = max(flash, strength)
        }
        if (strength > 0.30f) spawnDrops(strength)
        if (strength > 0.40f) happy = max(happy, if (strength > 0.7f) 1f else 0.7f)
        if (strength > 0.85f) {
            queueHaptic(2)
            glint = 0f
        }
        surprise = 0f
    }

    // ───────────────────────── per-frame (face, fx) ─────────────────────────

    private fun frameUpdate(dt: Float) {
        ambient += dt

        // Wordmark follows the first landing.
        if (startedAt >= 0f && finPhase == 0) {
            wordmark = ((time - startedAt - 0.05f) / 0.95f).coerceIn(0f, 1f)
        }

        // Mood decay.
        happy = max(0f, happy - dt / 0.75f)
        surprise = max(0f, surprise - dt / 0.45f)
        if (finPhase == 0) determined = max(0f, determined - dt / 0.3f)
        if (airborne && vLift > 0.8f) surprise = max(surprise, 0.6f)
        flash = max(0f, flash - dt / 0.55f)
        if (glint >= 0f) {
            glint += dt / 0.9f
            if (glint > 1f) glint = -1f
        }

        updateEyes(dt)
        updateFx(dt)

        val openTarget = if (airborne && vLift > 0.8f) 1.0f else 0.0f
        mouth += ((max(openTarget, surprise * 0.9f) - mouth) * (1f - exp(-dt * 16f)))
    }

    private fun updateEyes(dt: Float) {
        // Blink: a quick close / open every 1.2 - 2.8 s.
        blinkIn -= dt
        if (blinkIn <= 0f && blinkT < 0f) {
            blinkT = 0f
            blinkIn = 1.2f + 1.6f * nextFloat()
        }
        var blink = 0f
        if (blinkT >= 0f) {
            blinkT += dt
            val u = blinkT / 0.17f
            blink = if (u < 0.45f) u / 0.45f else 1f - (u - 0.45f) / 0.55f
            if (u >= 1f) blinkT = -1f
            blink = blink.coerceIn(0f, 1f)
        }
        // Squeeze the eyes shut while the body is squashed hard.
        val squeeze = ((-a - 0.14f) / 0.26f).coerceIn(0f, 1f)
        val wide = 1f + 0.22f * surprise
        val narrow = 1f - 0.42f * determined
        eyeOpen = max(0f, wide * narrow * (1f - max(blink, squeeze * 0.85f * (1f - determined))))

        // Where to look.
        var tx: Float
        var ty: Float
        if (pointerActive) {
            val cx = centerX + x * cubePx
            val cy = floorY - lift * cubePx - CENTER_RISE * cubePx
            tx = ((pointerX - cx) / (0.45f * width)).coerceIn(-1f, 1f)
            ty = ((cy - pointerY) / (0.30f * height)).coerceIn(-1f, 1f)
        } else {
            gazeIn -= dt
            if (gazeIn <= 0f) {
                gazeIn = 1.1f + 1.4f * nextFloat()
                val r = nextFloat()
                gazeX = if (r < 0.45f) 0f else if (r < 0.72f) -0.8f else 0.8f
                gazeY = if (nextFloat() < 0.3f) 0.7f else 0.05f
            }
            tx = gazeX + vx * 0.3f
            ty = gazeY
            if (finPhase != 0) {
                tx = 0f
                ty = 0.6f
            }
        }
        val k = 1f - exp(-dt * 10f)
        lookX += (tx - lookX) * k
        lookY += (ty - lookY) * k
    }

    private fun updateFx(dt: Float) {
        for (i in 0 until MAX_RINGS) {
            if (ringAge[i] >= 0f) {
                ringAge[i] += dt / RING_LIFE
                if (ringAge[i] > 1f) ringAge[i] = -1f
            }
        }
        for (i in 0 until MAX_DROPS) {
            if (dropLife[i] > 0f) {
                dropLife[i] -= dt
                dropVY[i] -= 15f * dt
                dropX[i] += dropVX[i] * dt
                dropY[i] += dropVY[i] * dt
                if (dropY[i] < 0f) {
                    dropY[i] = 0f
                    dropVY[i] = -dropVY[i] * 0.35f
                    dropVX[i] *= 0.7f
                }
            }
        }
    }

    private fun spawnRing(atX: Float, strength: Float) {
        var slot = 0
        var oldest = -1f
        for (i in 0 until MAX_RINGS) {
            if (ringAge[i] < 0f) {
                slot = i
                oldest = Float.MAX_VALUE
                break
            }
            if (ringAge[i] > oldest) {
                oldest = ringAge[i]
                slot = i
            }
        }
        ringX[slot] = atX
        ringAge[slot] = 0f
        ringStrength[slot] = strength
    }

    private fun spawnDrops(strength: Float) {
        val n = (4 + 10 * strength).toInt()
        var spawned = 0
        for (i in 0 until MAX_DROPS) {
            if (spawned >= n) break
            if (dropLife[i] > 0f) continue
            val ang = (PI.toFloat() * (0.12f + 0.76f * nextFloat()))
            val sp = 1.6f + 3.4f * strength * (0.5f + nextFloat())
            dropX[i] = x + (nextFloat() - 0.5f) * 0.9f
            dropY[i] = 0.04f
            dropVX[i] = kotlin.math.cos(ang) * sp * 0.9f
            dropVY[i] = sin(ang) * sp
            dropLifeMax[i] = 0.55f + 0.5f * nextFloat()
            dropLife[i] = dropLifeMax[i]
            dropSize[i] = 0.010f + 0.020f * nextFloat()
            spawned++
        }
    }

    /** 0..1 fade for a drop. */
    fun dropAlpha(i: Int): Float {
        val l = dropLifeMax[i]
        return if (l <= 0f) 0f else (dropLife[i] / l).coerceIn(0f, 1f)
    }

    private fun nextFloat(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFFFF) / 16777216f
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    companion object {
        const val MAX_RINGS = 6
        const val MAX_DROPS = 40
        const val RING_LIFE = 0.95f

        private const val SUB_STEP = 1f / 240f
        private const val G_HOP = 17.6f
        private const val G_DROP = 26f
        private const val MAX_X = 0.9f
        private const val DROP_RECOVER = 0.30f
        private const val USER_DEADLINE = 4.6f
        private const val BUFFER_TIME = 0.45f
        private const val CHARGE_TIME = 0.70f
        private const val FINALE_CROUCH = 0.24f
        private const val FINALE_LEAP = 0.66f

        /** Edge length of the cube in pixels for a viewport. */
        fun cubeSize(w: Float, h: Float): Float = max(1f, min(w * 0.34f, h * 0.27f))

        /** Screen Y of the floor for a viewport. */
        fun floorLine(w: Float, h: Float): Float = h * (if (w > h) 0.66f else 0.575f)

        /** Top of the wordmark: below the floor and the start of the reflection. */
        fun wordmarkTop(w: Float, h: Float): Float = min(floorLine(w, h) + cubeSize(w, h) * 1.12f, h * 0.88f)

        /** Silhouette height at rest, in cube edges (see [CubeGeometry]). */
        const val SILHOUETTE_H = 1.55f

        /** Height of the silhouette centre above the footprint centre, in cube edges. */
        const val CENTER_RISE = 0.4455f

        private const val TAU = 2f * PI.toFloat()
        private const val W_SQUASH = TAU * 3.0f
        private const val W_WOBBLE = TAU * 3.6f
        private const val W_LEAN = TAU * 2.6f
        private const val W_ROLL = TAU * 2.2f
        private const val W_YAW = TAU * 1.6f
    }
}
