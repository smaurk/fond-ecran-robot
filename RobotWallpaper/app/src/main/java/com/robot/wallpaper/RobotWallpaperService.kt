package com.robot.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class RobotWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RobotEngine()

    inner class RobotEngine : Engine() {

        // ---------- Reglages ----------
        private val COLOR = Color.parseColor("#50C878")  // vert emeraude
        private val BLINK_EVERY_MS = 5_000L  // un clignement toutes les 5 secondes
        private val BLINK_MS = 200L          // duree d'un clignement
        // --------------------------------

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
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

        // 1 = yeux ouverts, 0 = yeux fermes
        private fun openness(now: Long): Float {
            val t = now % BLINK_EVERY_MS
            if (t < BLINK_MS) {
                val p = t.toFloat() / BLINK_MS
                return 1f - sin(PI * p).toFloat()
            }
            return 1f
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

            val o = openness(System.currentTimeMillis())

            val eyeW = w * 0.24f
            val fullH = eyeW * 1.1f
            val minH = w * 0.012f
            val eyeH = minH + (fullH - minH) * o
            val cy = h * 0.5f

            for (cx in floatArrayOf(w * 0.30f, w * 0.70f)) {
                rect.set(cx - eyeW / 2, cy - eyeH / 2, cx + eyeW / 2, cy + eyeH / 2)
                val r = min(eyeW, eyeH) / 2f * 0.85f
                c.drawRoundRect(rect, r, r, eyePaint)
            }
        }
    }
}
