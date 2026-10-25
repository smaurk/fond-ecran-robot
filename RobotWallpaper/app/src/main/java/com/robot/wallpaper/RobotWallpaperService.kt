package com.robot.wallpaper

import android.app.WallpaperManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class RobotWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RobotEngine()

    inner class RobotEngine : Engine() {

        // ---------- Reglages ----------
        private val COLOR = Color.parseColor("#50C878")  // vert emeraude
        private val WAKE_OPEN_MS = 4_000L    // yeux ouverts au reveil de l'ecran
        private val TAP_OPEN_MS = 2_000L     // yeux ouverts apres une tape
        private val OPEN_SPEED_MS = 350f     // duree d'ouverture
        private val CLOSE_SPEED_MS = 500f    // duree de fermeture
        private val BLINK_MS = 160L          // duree d'un clignement
        private val TIME_SIZE = 0.12f        // taille de l'heure
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var touching = false
        private var wakeUntil = 0L
        private var cur = 0f
        private var lastFrame = 0L
        private var nextBlink = 0L

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val rect = RectF()

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
                val now = System.currentTimeMillis()
                wakeUntil = now + WAKE_OPEN_MS
                lastFrame = now
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
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int,
            extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                wakeUntil = System.currentTimeMillis() + TAP_OPEN_MS
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        private fun blinkFactor(now: Long): Float {
            if (now < nextBlink) return 1f
            val p = (now - nextBlink).toFloat() / BLINK_MS
            if (p >= 1f) {
                nextBlink = now + Random.nextLong(2500, 5000)
                return 1f
            }
            return 1f - sin(PI * p).toFloat()
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

            val target = if (touching || now < wakeUntil) 1f else 0f
            cur = if (cur < target) min(target, cur + dt / OPEN_SPEED_MS)
                  else maxOf(target, cur - dt / CLOSE_SPEED_MS)

            var o = smooth(cur)
            if (cur >= 0.99f) o *= blinkFactor(now)

            val eyeW = w * 0.24f
            val fullH = eyeW * 1.1f
            val minH = w * 0.012f
            val eyeH = minH + (fullH - minH) * o
            val cy = h * (0.44f + 0.04f * o)

            for (cx in floatArrayOf(w * 0.30f, w * 0.70f)) {
                rect.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2)
                val r = min(eyeW, eyeH) / 2f * 0.85f
                c.drawRoundRect(rect, r, r, eyePaint)
            }

            // L'heure reste affichee en permanence
            textPaint.textSize = w * TIME_SIZE
            c.drawText(timeFormat.format(Date(now)), w / 2f, h * 0.66f, textPaint)
        }
    }
}
