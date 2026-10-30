package com.robot.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

private const val PI_F = 3.1415927f

object RobotState {
    @Volatile var lastNotif = 0L
}

class RobotNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return
        RobotState.lastNotif = System.currentTimeMillis()
    }
}

class RobotWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RobotEngine()

    inner class RobotEngine : Engine() {

        // ---------- Reglages ----------
        private val COLOR = Color.parseColor("#50C878")  // vert emeraude
        private val SPIDER_COLOR = Color.parseColor("#FF7518")  // orange halloween
        private val BLINK_EVERY_MS = 5_000L  // clignement toutes les 5 secondes
        private val BLINK_MS = 200L          // duree d'un clignement
        private val NOTIF_MS = 4_000L        // duree du regard vers le haut
        private val CIRCLE_EVERY_MS = 2_000L // un tour de cercle toutes les 2 secondes
        private val CIRCLE_MS = 900L         // duree d'un tour
        private val CIRCLE_SIZE = 1.0f       // taille du cercle (1 = le plus grand possible)
        private val WAKE_MS = 2_700L         // duree de l'expression a l'allumage
        private val PUPIL_SIZE = 0.17f       // taille de la pupille
        private val EYE_WIDTH = 0.22f        // largeur d'un oeil (part de l'ecran)
        private val EYE_RATIO = 1.25f        // hauteur / largeur de l'oeil
        private val WEB_SIZE = 0.30f         // taille de la toile (part de la largeur)
        private val SPIDER_CYCLE_MS = 10_000L // duree d'un aller-retour de l'araignee
        private val SPIDER_DROP = 0.18f      // descente max (part de la hauteur)
        private val SWING_ANGLE = 0.40f      // amplitude du balancement (en radians)
        private val SWING_PERIOD_MS = 2_400L // duree d'un aller-retour du balancement
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        private var seenNotif = RobotState.lastNotif
        private var notifStart = -100_000L
        private var wakeStart = -100_000L
        private var lastFrame = 0L
        private var gazeX = 0f
        private var gazeY = 0f

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val webPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
        }
        private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SPIDER_COLOR
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SPIDER_COLOR }
        private val rect = RectF()
        private val path = Path()
        private val webPath = Path()

        private val drawRunner = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 33)
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(drawRunner)
            if (isVisible) {
                val now = System.currentTimeMillis()
                lastFrame = now
                wakeStart = now   // l'ecran s'allume : surprise puis joie
                handler.post(drawRunner)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
        }

        // 1 = yeux ouverts, 0 = yeux fermes
        private fun openness(now: Long): Float {
            val t: Long = now % BLINK_EVERY_MS
            if (t < BLINK_MS) {
                val p: Float = t.toFloat() / BLINK_MS.toFloat()
                return 1f - sin(PI_F * p)
            }
            return 1f
        }

        private fun smooth(x: Float): Float {
            val c: Float = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        // Toile d'araignee dans le coin en haut a gauche
        private fun drawWeb(c: Canvas, w: Float) {
            val len: Float = w * WEB_SIZE
            val quarter: Float = PI_F / 2f
            webPaint.strokeWidth = w * 0.003f

            for (i in 0..4) {
                val a: Float = quarter * i.toFloat() / 4f
                c.drawLine(0f, 0f, cos(a) * len, sin(a) * len, webPaint)
            }
            for (j in 1..4) {
                val r: Float = len * j.toFloat() / 4f
                webPath.reset()
                for (i in 0 until 4) {
                    val a1: Float = quarter * i.toFloat() / 4f
                    val a2: Float = quarter * (i + 1).toFloat() / 4f
                    val am: Float = (a1 + a2) / 2f
                    if (i == 0) webPath.moveTo(cos(a1) * r, sin(a1) * r)
                    val cr: Float = r * 0.9f
                    webPath.quadTo(cos(am) * cr, sin(am) * cr, cos(a2) * r, sin(a2) * r)
                }
                c.drawPath(webPath, webPaint)
            }
        }

        // Petite araignee qui descend au bout de son fil en se balancant
        private fun drawSpider(c: Canvas, w: Float, h: Float, now: Long) {
            val t: Float = (now % SPIDER_CYCLE_MS).toFloat()
            val f: Float = when {
                t < 5000f -> smooth(t / 5000f)
                t < 6500f -> 1f
                else -> 1f - smooth((t - 6500f) / 3500f)
            }
            val b: Float = w * 0.020f

            // Point d'attache du fil et longueur du fil
            val ax: Float = w * 0.14f
            val len: Float = h * 0.03f + h * SPIDER_DROP * f

            // Balancement de gauche a droite (pendule)
            val swingPhase: Float = (now % SWING_PERIOD_MS).toFloat() / SWING_PERIOD_MS.toFloat()
            val swing: Float = SWING_ANGLE * sin(swingPhase * 2f * PI_F)
            val sx: Float = ax + sin(swing) * len
            val sy: Float = cos(swing) * len

            // Fil
            webPaint.strokeWidth = w * 0.0025f
            c.drawLine(ax, 0f, sx, sy - b, webPaint)

            // Pattes
            legPaint.strokeWidth = w * 0.004f
            val wiggleBase: Float = (now % 1000L).toFloat() / 1000f * 2f * PI_F * 8f
            for (side in intArrayOf(-1, 1)) {
                val sd: Float = side.toFloat()
                for (i in 0..3) {
                    val fi: Float = i.toFloat()
                    val wig: Float = sin(wiggleBase + fi) * b * 0.25f
                    val x0: Float = sx
                    val y0: Float = sy + b * 0.3f
                    val kx: Float = sx + sd * b * 1.8f
                    val ky: Float = sy + (fi - 1.5f) * b * 0.9f - b * 0.8f
                    val fx: Float = sx + sd * b * 2.8f
                    val fy: Float = sy + (fi - 1.5f) * b * 1.3f + b * 1.2f + wig
                    c.drawLine(x0, y0, kx, ky, legPaint)
                    c.drawLine(kx, ky, fx, fy, legPaint)
                }
            }

            // Corps : abdomen + tete
            c.drawCircle(sx, sy, b, bodyPaint)
            val hy: Float = sy + b * 1.15f
            c.drawCircle(sx, hy, b * 0.62f, bodyPaint)
            c.drawCircle(sx - b * 0.22f, hy + b * 0.1f, b * 0.14f, glintPaint)
            c.drawCircle(sx + b * 0.22f, hy + b * 0.1f, b * 0.14f, glintPaint)
        }

        private fun render(c: Canvas) {
            val w: Float = c.width.toFloat()
            val h: Float = c.height.toFloat()
            c.drawColor(Color.BLACK)

            val now: Long = System.currentTimeMillis()
            val dt: Float = (now - lastFrame).coerceIn(0L, 100L).toFloat()
            lastFrame = now
            val k: Float = 1f - exp(-dt / 120f)

            // Decor d'Halloween
            drawWeb(c, w)
            drawSpider(c, w, h, now)

            if (RobotState.lastNotif != seenNotif) {
                seenNotif = RobotState.lastNotif
                notifStart = now
            }

            val sinceNotif: Long = now - notifStart
            val sinceWake: Long = now - wakeStart
            val wakeMode: Boolean = sinceWake >= 0L && sinceWake < WAKE_MS
            val notifMode: Boolean = !wakeMode && sinceNotif >= 0L && sinceNotif < NOTIF_MS

            // Expression a l'allumage : 1) surprise, 2) joie
            var surprise = 0f
            var joy = 0f
            if (wakeMode) {
                val tw: Float = sinceWake.toFloat()
                surprise = smooth(tw / 150f) * (1f - smooth((tw - 700f) / 300f))
                joy = smooth((tw - 600f) / 300f) * (1f - smooth((tw - 2300f) / 400f))
            }

            // Regard de base
            val phase: Float = (now % 100_000L).toFloat() / 2300f
            val drift: Float = sin(phase) * 0.08f
            val tgx: Float = if (notifMode || wakeMode) 0f else drift
            var tgy: Float = if (notifMode) -0.85f else 0f
            if (wakeMode) tgy = -0.3f * surprise
            gazeX += (tgx - gazeX) * k
            gazeY += (tgy - gazeY) * k

            val o: Float = if (wakeMode) 1f else openness(now)
            val eyeW: Float = w * EYE_WIDTH * (1f + 0.2f * surprise)
            val fullH: Float = eyeW * EYE_RATIO
            val minH: Float = w * 0.012f
            val eyeH: Float = minH + (fullH - minH) * o
            val cy: Float = h * 0.5f
            val pupilR: Float = eyeW * PUPIL_SIZE * (1f - 0.3f * surprise)

            // Deplacement maximal de la pupille dans l'oeil (en pixels)
            val maxX: Float = (eyeW / 2f - pupilR) * 0.9f
            val maxY: Float = (fullH / 2f - pupilR) * 0.9f

            // Tour de cercle toutes les 2 secondes
            val circleR: Float = min(maxX, maxY) * CIRCLE_SIZE
            var cirX = 0f
            var cirY = 0f
            val t1: Long = now % CIRCLE_EVERY_MS
            if (!notifMode && !wakeMode && t1 < CIRCLE_MS) {
                val p: Float = t1.toFloat() / CIRCLE_MS.toFloat()
                val env: Float = smooth(min(p / 0.10f, (1f - p) / 0.10f)) * o
                val a: Float = p * 2f * PI_F - PI_F / 2f
                cirX = cos(a) * circleR * env
                cirY = sin(a) * circleR * env
            }

            val centers = floatArrayOf(w * 0.31f, w * 0.69f)
            for (cx in centers) {
                rect.set(cx - eyeW / 2f, cy - eyeH / 2f, cx + eyeW / 2f, cy + eyeH / 2f)
                val r: Float = min(eyeW, eyeH) / 2f
                path.reset()
                path.addRoundRect(rect, r, r, Path.Direction.CW)
                c.drawPath(path, eyePaint)

                // La pupille reste toujours a l'interieur de l'oeil
                c.save()
                c.clipPath(path)
                val px: Float = cx + gazeX * maxX + cirX
                val py: Float = cy + gazeY * maxY + cirY
                c.drawCircle(px, py, pupilR, pupilPaint)
                c.drawCircle(px + pupilR * 0.35f, py - pupilR * 0.35f, pupilR * 0.28f, glintPaint)
                c.restore()

                // Yeux joyeux : un grand cercle noir remonte et ne laisse qu'un arc
                if (joy > 0.01f) {
                    val cutR: Float = eyeW * 0.75f
                    val cutStart: Float = cy + eyeH / 2f + cutR
                    val cutEnd: Float = cy - eyeH / 2f + cutR + eyeW * 0.28f
                    val cutY: Float = cutStart + (cutEnd - cutStart) * joy
                    c.drawCircle(cx, cutY, cutR, cutPaint)
                }
            }
        }
    }
}