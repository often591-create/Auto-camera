package com.autocamera.helper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/** 拍摄参数水印：只写手机型号与参数，不放任何相机品牌的标志。 */
object Watermark {

    data class Info(val model: String, val params: String, val date: String)

    fun info(exif: ExifInterface?, prefs: HelperPrefs, fallbackModel: String): Info {
        val model = prefs.displayName.ifBlank {
            val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val m = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            when {
                m.isBlank() -> fallbackModel
                make.isNotBlank() && !m.startsWith(make, true) -> "$make $m"
                else -> m
            }
        }
        val parts = mutableListOf<String>()
        if (exif != null) {
            val f35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            if (f35 > 0) parts.add("${f35}mm") else {
                val fl = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (fl > 0) parts.add(String.format("%.1fmm", fl))
            }
            val fn = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (fn > 0) parts.add("f/" + (if (fn >= 10) fn.roundToInt().toString() else String.format("%.1f", fn)))
            val et = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (et > 0) parts.add(if (et >= 1) "${et.roundToInt()}s" else "1/${(1 / et).roundToInt()}s")
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            if (iso > 0) parts.add("ISO$iso")
        }
        val dt = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME) ?: ""
        val date = if (dt.length >= 16) dt.substring(0, 10).replace(':', '.') + " " + dt.substring(11, 16) else dt
        return Info(model, parts.joinToString("  "), date)
    }

    /** 返回加好水印的新图（白边样式会变高）。style 0 时原样返回。 */
    fun apply(src: Bitmap, info: Info, prefs: HelperPrefs): Bitmap {
        val model = if (prefs.wmModel) info.model else ""
        val params = if (prefs.wmParams) info.params else ""
        val date = if (prefs.wmDate) info.date else ""
        if (prefs.wmStyle == 0 || (model.isBlank() && params.isBlank() && date.isBlank())) return src
        val w = src.width
        val h = src.height
        val base = minOf(w, h)
        return when (prefs.wmStyle) {
            1 -> {
                val bar = (base * 0.11f).roundToInt()
                val out = Bitmap.createBitmap(w, h + bar, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Color.WHITE)
                c.drawBitmap(src, 0f, 0f, null)
                val pad = bar * 0.45f
                val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(20, 20, 20); textSize = bar * 0.27f; typeface = Typeface.DEFAULT_BOLD
                }
                val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 120); textSize = bar * 0.19f }
                val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 30, 30); textSize = bar * 0.22f; textAlign = Paint.Align.RIGHT
                }
                val top = h.toFloat()
                if (model.isNotBlank()) c.drawText(model, pad, top + bar * (if (date.isBlank()) 0.6f else 0.47f), big)
                if (date.isNotBlank()) c.drawText(date, pad, top + bar * (if (model.isBlank()) 0.58f else 0.78f), small)
                if (params.isNotBlank()) {
                    c.drawText(params, w - pad, top + bar * 0.58f, mid)
                    val line = Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = max(2f, bar * 0.012f) }
                    val lx = w - pad - mid.measureText(params) - bar * 0.25f
                    c.drawLine(lx, top + bar * 0.3f, lx, top + bar * 0.7f, line)
                }
                src.recycle()
                out
            }
            else -> {
                val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = base * 0.024f; textAlign = Paint.Align.RIGHT
                    setShadowLayer(base * 0.006f, 0f, 0f, Color.argb(160, 0, 0, 0))
                }
                val line = listOf(model, params, date).filter { it.isNotBlank() }.joinToString("  ·  ")
                c.drawText(line, w - base * 0.03f, h - base * 0.03f, p)
                out
            }
        }
    }
}
