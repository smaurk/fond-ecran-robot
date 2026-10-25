package com.robot.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class RobotWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RobotEngine()

    inner class RobotEngine : Engine() {

        // ---------- Reglages ----------
        private val COLOR = Color.parseColor("#50C878")   // vert emeraude
        private val CYCLE_MS = 30_000L        // duree d'un cycle complet
        private val AWAKE_MS = 20_000L        // yeux ouverts (avec clignements)
        private val TRANSITION_MS = 400L      // fermeture / ouverture
        private val BLINK_MS = 160L           // duree d'un clignement
        private val BLINK_TIMES = longArrayOf(3_000, 8_500, 12_000, 17_000)
        private val TIME_SIZE = 0.12f         // taille de l'heure (part de la largeur)
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

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

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(drawRunner)
            if (isVisible) handler.post(drawRunner)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
        }

        // Ouverture des yeux : 1 = grands ouverts, 0 = fermes
        private fun openness(t: Long): Float {
            if (t < AWAKE_MS) {
                for (b in BLINK_TIMES) {
                    if (t >= b && t < b + BLINK_MS) {
                        val p = (t - b).toFloat() / BLINK_MS
                        return 1f - sin(PI * p).toFloat()
                    }
                }
                return 1f
            }
            val closedEnd = CYCLE_MS - TRANSITION_MS
            return when {
                t < AWAKE_MS + TRANSITION_MS ->
                    1f - smooth((t - AWAKE_MS).toFloat() / TRANSITION_MS)
                t < closedEnd -> 0f
                else -> smooth((t - closedEnd).toFloat() / TRANSITION_MS)
            }
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
            val t = now % CYCLE_MS
            val o = openness(t)

            val eyeW = w * 0.24f
            val fullH = eyeW * 1.1f
            val minH = w * 0.012f
            val eyeH = minH + (fullH - minH) * o
            val cy = h * (0.50f + 0.05f * o)

            for (cx in floatArrayOf(w * 0.30f, w * 0.70f)) {
                rect.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2)
                val r = min(eyeW, eyeH) / 2f * 0.85f
                c.drawRoundRect(rect, r, r, eyePaint)
            }

            // L'heure apparait quand les yeux se ferment
            val alpha = ((1f - o) * 255).toInt().coerceIn(0, 255)
            if (alpha > 0) {
                textPaint.textSize = w * TIME_SIZE
                textPaint.alpha = alpha
                c.drawText(timeFormat.format(Date(now)), w / 2f, h * 0.64f, textPaint)
            }
        }
    }
}
