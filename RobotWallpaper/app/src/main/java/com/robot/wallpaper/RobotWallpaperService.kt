package com.robot.wallpaper

import android.app.WallpaperManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.PI
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
        private val BLINK_EVERY_MS = 5_000L  // clignement automatique
        private val BLINK_MS = 200L          // duree d'un clignement
        private val NOTIF_MS = 4_000L        // duree du regard vers le haut
        private val TAP_LOOK_MS = 1_200L     // duree du regard apres une tape
        private val PUPIL_SIZE = 0.17f       // taille de la pupille
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        private var touching = false
        private var touchX = 0f
        private var touchY = 0f
        private var tapUntil = 0L
        private var tapX = 0f
        private var tapY = 0f
        private var blinkStart = -100_000L
        private var seenNotif = RobotState.lastNotif
        private var notifStart = -100_000L
        private var lastFrame = 0L

        private val gx = FloatArray(2)
        private val gy = FloatArray(2)

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rect = RectF()
        private val path = Path()

        private val drawRunner = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 33)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(drawRunner)
            if (isVisible) {
                lastFrame = System.currentTimeMillis()
                handler.post(drawRunner)
            } else {
                touching = false
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touching = true
                    touchX = event.x
                    touchY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    blinkStart = System.currentTimeMillis()
                }
            }
            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int,
            extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                val now = System.currentTimeMillis()
                tapX = x.toFloat()
                tapY = y.toFloat()
                tapUntil = now + TAP_LOOK_MS
                blinkStart = tapUntil
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun openness(now: Long): Float {
            var o = 1f
            val t = now % BLINK_EVERY_MS
            if (t < BLINK_MS) {
                val p = t.toFloat() / BLINK_MS
                o = min(o, 1f - sin(PI * p).toFloat())
            }
            val bt = now - blinkStart
            if (bt in 0 until BLINK_MS) {
                val p = bt.toFloat() / BLINK_MS
                o = min(o, 1f - sin(PI * p).toFloat())
            }
            return o
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
            val k = (dt / 100f).coerceIn(0f, 1f)

            if (RobotState.lastNotif != seenNotif) {
                seenNotif = RobotState.lastNotif
                notifStart = now
            }

            val touchMode = touching || now < tapUntil
            val notifMode = !touchMode && (now - notifStart) in 0 until NOTIF_MS
            val tx = if (touching) touchX else tapX
            val ty = if (touching) touchY else tapY

            val o = openness(now)
            val eyeW = w * 0.24f
            val fullH = eyeW * 1.1f
            val minH = w * 0.012f
            val eyeH = minH + (fullH - minH) * o
            val cy = h * 0.5f

            for (i in 0..1) {
                val cx = w * (if (i == 0) 0.30f else 0.70f)

                var tgx = 0f
                var tgy = 0f
                if (touchMode) {
                    tgx = ((tx - cx) / (w * 0.35f)).coerceIn(-1f, 1f)
                    tgy = ((ty - cy) / (h * 0.2f)).coerceIn(-1f, 1f)
                } else if (notifMode) {
                    tgy = -1f
                }
                gx[i] += (tgx - gx[i]) * k
                gy[i] += (tgy - gy[i]) * k

                rect.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2)
                val r = min(eyeW, eyeH) / 2f * 0.85f
                path.reset()
                path.addRoundRect(rect, r, r, Path.Direction.CW)
                c.drawPath(path, eyePaint)

                // Pupille noire, coupee par la forme de l'oeil
                c.save()
                c.clipPath(path)
                val px = cx + gx[i] * eyeW * 0.26f
                val py = cy + gy[i] * fullH * 0.24f
                c.drawCircle(px, py, eyeW * PUPIL_SIZE, pupilPaint)
                c.restore()
            }
        }
    }
}