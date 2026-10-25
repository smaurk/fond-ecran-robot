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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

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
        private val BLINK_EVERY_MS = 5_000L  // clignement toutes les 5 secondes
        private val BLINK_MS = 200L          // duree d'un clignement
        private val NOTIF_MS = 4_000L        // duree du regard vers le haut
        private val CIRCLE_EVERY_MS = 1_000L // un tour de cercle toutes les secondes
        private val CIRCLE_MS = 900L         // duree d'un tour
        private val CIRCLE_SIZE = 2.0f       // taille du cercle (1 = le plus grand possible)
        private val PUPIL_SIZE = 0.17f       // taille de la pupille
        private val EYE_WIDTH = 0.22f        // largeur d'un oeil (part de l'ecran)
        private val EYE_RATIO = 1.25f        // hauteur / largeur de l'oeil
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        private var seenNotif = RobotState.lastNotif
        private var notifStart = -100_000L
        private var lastFrame = 0L
        private var gazeX = 0f
        private var gazeY = 0f

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val path = Path()

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
                lastFrame = System.currentTimeMillis()
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
            val t = now % BLINK_EVERY_MS
            if (t < BLINK_MS) {
                val p = t.toFloat() / BLINK_MS
                return 1f - sin(PI * p).toFloat()
            }
            return 1f
        }

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
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

        private fun render(c: Canvas) {
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            c.drawColor(Color.BLACK)

            val now = System.currentTimeMillis()
            val dt = (now - lastFrame).coerceIn(0L, 100L).toFloat()
            lastFrame = now
            val k = 1f - exp(-dt / 120f)

            if (RobotState.lastNotif != seenNotif) {
                seenNotif = RobotState.lastNotif
                notifStart = now
            }
            val notifMode = (now - notifStart) in 0L until NOTIF_MS

            // Regard de base : leger balayage au repos, vers le haut pour une notification
            val drift = sin((now % 100_000L) / 2300f) * 0.08f
            val tgx = if (notifMode) 0f else drift
            val tgy = if (notifMode) -0.85f else 0f
            gazeX += (tgx - gazeX) * k
            gazeY += (tgy - gazeY) * k

            val o = openness(now)
            val eyeW = w * EYE_WIDTH
            val fullH = eyeW * EYE_RATIO
            val minH = w * 0.012f
            val eyeH = minH + (fullH - minH) * o
            val cy = h * 0.5f
            val pupilR = eyeW * PUPIL_SIZE

            // Deplacement maximal de la pupille dans l'oeil (en pixels)
            val maxX = (eyeW / 2f - pupilR) * 0.9f
            val maxY = (fullH / 2f - pupilR) * 0.9f

            // Tour de cercle toutes les secondes
            val circleR = min(maxX, maxY) * CIRCLE_SIZE
            var cirX = 0f
            var cirY = 0f
            val t1 = now % CIRCLE_EVERY_MS
            if (!notifMode && t1 < CIRCLE_MS) {
                val p = t1.toFloat() / CIRCLE_MS
                val env = smooth(min(p / 0.10f, (1f - p) / 0.10f)) * o
                val a = p * 2f * PI.toFloat() - PI.toFloat() / 2f
                cirX = cos(a) * circleR * env
                cirY = sin(a) * circleR * env
            }

            for (cx in floatArrayOf(w * 0.31f, w * 0.69f)) {
                rect.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2)
                val r = min(eyeW, eyeH) / 2f
                path.reset()
                path.addRoundRect(rect, r, r, Path.Direction.CW)
                c.drawPath(path, eyePaint)

                // La pupille reste toujours a l'interieur de l'oeil
                c.save()
                c.clipPath(path)
                val px = cx + gazeX * maxX + cirX
                val py = cy + gazeY * maxY + cirY
                c.drawCircle(px, py, pupilR, pupilPaint)
                c.drawCircle(px + pupilR * 0.35f, py - pupilR * 0.35f, pupilR * 0.28f, glintPaint)
                c.restore()
            }
        }
    }
}