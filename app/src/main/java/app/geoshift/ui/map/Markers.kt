package app.geoshift.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable

/** Simulated position while simulating: fresh green, as the "模拟中" state elsewhere in the app. */
private const val SIM_ACTIVE = 0xFF00B386.toInt()
/** Simulated position while idle: the app's violet, clearly not the system-blue real position. */
private const val SIM_IDLE = 0xFF6C5CE7.toInt()
/** Real position: the blue every map app uses for "my location". */
private const val REAL = 0xFF1A7CFF.toInt()

/**
 * Position markers with a name tag under the dot. The bitmap is padded so the dot sits exactly in
 * the centre: every map anchors it at (0.5, 0.5).
 */
private fun labelledDot(context: Context, label: String, color: Int, filledTag: Boolean, big: Boolean): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val halo = (if (big) 22f else 17f) * d
    val ring = (if (big) 11f else 8.5f) * d
    val core = (if (big) 7.5f else 6f) * d

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.color = if (filledTag) 0xFFFFFFFF.toInt() else color
    }
    val tagW = text.measureText(label) + 16 * d
    val tagH = 20 * d
    val gap = 3 * d
    val half = halo + gap + tagH + 3 * d // dot centre to the bottom of the tag (plus shadow room)
    val w = maxOf(2 * halo, tagW + 8 * d).toInt() + 2
    val h = (2 * half).toInt() + 2
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    val cy = h / 2f

    // Soft halo, white ring with a shadow, coloured core.
    p.color = (color and 0x00FFFFFF) or 0x38000000
    c.drawCircle(cx, cy, halo, p)
    p.color = 0xFFFFFFFF.toInt()
    p.setShadowLayer(3 * d, 0f, 1 * d, 0x50000000)
    c.drawCircle(cx, cy, ring, p)
    p.clearShadowLayer()
    p.color = color
    c.drawCircle(cx, cy, core, p)
    if (big) { // simulated: a crosshair-like white centre makes it a different shape, not just a colour
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, 2.6f * d, p)
    }

    // Name tag.
    val top = cy + halo + gap
    val rect = RectF(cx - tagW / 2, top, cx + tagW / 2, top + tagH)
    p.color = if (filledTag) color else 0xFFFFFFFF.toInt()
    p.setShadowLayer(2.5f * d, 0f, 1 * d, 0x40000000)
    c.drawRoundRect(rect, tagH / 2, tagH / 2, p)
    p.clearShadowLayer()
    if (!filledTag) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = color
        c.drawRoundRect(rect, tagH / 2, tagH / 2, p)
        p.style = Paint.Style.FILL
    }
    val fm = text.fontMetrics
    c.drawText(label, cx - text.measureText(label) / 2, rect.centerY() - (fm.ascent + fm.descent) / 2, text)
    return BitmapDrawable(context.resources, bitmap)
}

/** The simulated (virtual) position: big, violet when idle, green while simulating, filled tag. */
internal fun simMarker(context: Context, active: Boolean): BitmapDrawable =
    labelledDot(context, if (active) "模拟中" else "模拟位置", if (active) SIM_ACTIVE else SIM_IDLE, filledTag = true, big = true)

/** The phone's real position: smaller blue dot, outlined tag. */
internal fun realMarker(context: Context): BitmapDrawable =
    labelledDot(context, "真实位置", REAL, filledTag = false, big = false)
