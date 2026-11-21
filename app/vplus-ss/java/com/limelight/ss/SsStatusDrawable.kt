package com.limelight.ss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.limelight.R

/**
 * Filled disc matching the other toolbar buttons; its colour carries the SS state:
 * grey = off, green = connected, orange = some node unreachable.
 * The white mesh glyph has hollow nodes while off or still checking, filled once nodes answered.
 */
class SsStatusDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val size = (24 * density).toInt()
    private val online = ContextCompat.getColor(context, R.color.ss_status_online)
    private val warning = ContextCompat.getColor(context, R.color.ss_status_warning)
    private val off = ContextCompat.getColor(context, R.color.ss_status_off)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var alphaValue = 255

    var status: SsRoutes.Status = SsRoutes.Status.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private fun discColor() = when (status) {
        SsRoutes.Status.OFF -> off
        SsRoutes.Status.CHECKING, SsRoutes.Status.ONLINE -> online
        SsRoutes.Status.DEGRADED -> warning
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val unit = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - 12 * unit, b.exactCenterY() - 12 * unit)

        fill.color = discColor()
        fill.alpha = alphaValue
        canvas.drawCircle(12 * unit, 12 * unit, 10 * unit, fill)

        // Triangle mesh: top, bottom-left, bottom-right nodes.
        val nodes = arrayOf(floatArrayOf(12f, 7.2f), floatArrayOf(7.6f, 14.9f), floatArrayOf(16.4f, 14.9f))
        val solid = status == SsRoutes.Status.ONLINE || status == SsRoutes.Status.DEGRADED
        val r = 2f * unit
        stroke.alpha = alphaValue
        stroke.strokeWidth = 1.5f * unit
        for (i in nodes.indices) {
            val a = nodes[i]
            val c = nodes[(i + 1) % nodes.size]
            // Stop the links at the node rims so hollow nodes stay clean.
            val dx = c[0] - a[0]
            val dy = c[1] - a[1]
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val ox = dx / len * 2f
            val oy = dy / len * 2f
            canvas.drawLine((a[0] + ox) * unit, (a[1] + oy) * unit, (c[0] - ox) * unit, (c[1] - oy) * unit, stroke)
        }
        fill.color = Color.WHITE
        fill.alpha = alphaValue
        for (n in nodes) {
            if (solid) canvas.drawCircle(n[0] * unit, n[1] * unit, r, fill)
            else canvas.drawCircle(n[0] * unit, n[1] * unit, r - 0.75f * unit, stroke)
        }
        canvas.restore()
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
