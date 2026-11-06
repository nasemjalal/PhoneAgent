package com.phoneagent.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

// Glowing animated pill, like the Gemini Live orb
// state: 0 idle, 1 listening, 2 working, 3 speaking
class OrbView(ctx: Context) : View(ctx) {

    @Volatile var state = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val t = System.currentTimeMillis() / 1000.0
        val speed = when (state) { 1 -> 3.0; 2 -> 7.0; 3 -> 5.0; else -> 1.2 }
        val amp = when (state) { 0 -> 0.05f; 1 -> 0.14f; 2 -> 0.2f; else -> 0.18f }
        val col = when (state) {
            2 -> 0xFFB06BFF.toInt()
            3 -> 0xFF4DD0E1.toInt()
            else -> 0xFF4C8DFF.toInt()
        }
        val k = (Math.sin(t * speed) * 0.5 + 0.5).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        for (i in 0..3) {
            val f = 1f - i * 0.2f
            val grow = 1f + amp * k * (4 - i) / 4f
            val rw = width / 2f * 0.85f * f * grow
            val rh = height / 2f * 0.85f * f * grow
            p.color = col
            p.alpha = 35 + i * 55
            c.drawRoundRect(cx - rw, cy - rh, cx + rw, cy + rh, rh, rh, p)
        }
        postInvalidateOnAnimation()
    }
}
