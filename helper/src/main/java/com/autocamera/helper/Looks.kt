package com.autocamera.helper

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 风格滤镜。名称都是自己取的，不使用任何相机品牌的商标；说明里描述风格方向。
 * contrast：S 曲线强度（负数为降低对比）；lift：黑位提亮（褪色感）；gamma：<1 变亮、>1 变暗；
 * shadow/highlight：暗部、亮部的 RGB 偏色；sat：饱和度；vignette：暗角；grain：颗粒；glow：柔光。
 */
data class Look(
    val id: String,
    val name: String,
    val desc: String,
    val contrast: Float = 0f,
    val lift: Float = 0f,
    val gamma: Float = 1f,
    val shadow: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlight: FloatArray = floatArrayOf(0f, 0f, 0f),
    val sat: Float = 1f,
    val bw: Boolean = false,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val glow: Float = 0f
)

object Looks {
    val all = listOf(
        Look("none", "原图", "不加滤镜，只加水印"),
        Look("street_bw", "街头黑白", "高对比黑白与颗粒，适合街拍（扫街相机风格）",
            contrast = 0.9f, gamma = 1.05f, bw = true, vignette = 0.25f, grain = 9f),
        Look("positive", "正片", "鲜艳正片色，暗部偏青、亮部偏暖",
            contrast = 0.55f, sat = 1.25f, shadow = floatArrayOf(-0.01f, 0.01f, 0.03f), highlight = floatArrayOf(0.03f, 0.01f, -0.02f), vignette = 0.18f),
        Look("natural", "自然人像", "柔和自然、肤色干净（中画幅风格）",
            contrast = 0.12f, lift = 0.015f, gamma = 0.97f, sat = 0.95f, highlight = floatArrayOf(0.015f, 0.005f, -0.01f)),
        Look("clear", "清透", "通透锐利、略偏冷（德系镜头风格）",
            contrast = 0.35f, gamma = 0.95f, sat = 1.05f, highlight = floatArrayOf(-0.005f, 0.005f, 0.02f)),
        Look("classic", "经典浓郁", "浓郁深沉、暗角明显（经典旁轴风格）",
            contrast = 0.55f, gamma = 1.08f, sat = 1.15f, shadow = floatArrayOf(0.015f, 0.005f, -0.005f), vignette = 0.35f),
        Look("film_warm", "胶片暖调", "褪色暖调与颗粒，有底片感",
            contrast = 0.2f, lift = 0.06f, sat = 0.9f, highlight = floatArrayOf(0.04f, 0.02f, -0.03f), grain = 7f),
        Look("soft", "柔光", "亮部柔和发光，适合人像与逆光",
            contrast = -0.08f, lift = 0.02f, sat = 0.95f, glow = 0.35f)
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]

    private fun lut(l: Look, ch: Int): IntArray = IntArray(256) { i ->
        var x = (i / 255f).pow(l.gamma)
        val s = x * x * (3 - 2 * x)
        x += (s - x) * l.contrast
        val lo = l.lift + l.shadow[ch]
        val hi = 1f + l.highlight[ch]
        x = lo + x * (hi - lo)
        (x.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    /** 直接修改传入的可变 Bitmap。 */
    fun apply(bmp: Bitmap, l: Look) {
        if (l.id == "none") return
        val w = bmp.width
        val h = bmp.height
        val lr = lut(l, 0); val lg = lut(l, 1); val lb = lut(l, 2)
        val cx = w / 2f; val cy = h / 2f
        val maxD2 = cx * cx + cy * cy
        var seed = 0x2545F491
        var glowPx: IntArray? = null
        var gw = 0; var gh = 0
        if (l.glow > 0f) {
            gw = max(8, w / 24); gh = max(8, h / 24)
            val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
            val tiny = Bitmap.createScaledBitmap(small, max(4, gw / 3), max(4, gh / 3), true)
            val back = Bitmap.createScaledBitmap(tiny, gw, gh, true)
            glowPx = IntArray(gw * gh); back.getPixels(glowPx, 0, gw, 0, 0, gw, gh)
            small.recycle(); tiny.recycle(); back.recycle()
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val dy = y - cy
            for (x in 0 until w) {
                val c = row[x]
                var r = lr[(c shr 16) and 0xff].toFloat()
                var g = lg[(c shr 8) and 0xff].toFloat()
                var b = lb[c and 0xff].toFloat()
                if (glowPx != null) {
                    val gp = glowPx[min(gh - 1, y * gh / h) * gw + min(gw - 1, x * gw / w)]
                    val k = l.glow
                    r = 255f - (255f - r) * (255f - ((gp shr 16) and 0xff) * k) / 255f
                    g = 255f - (255f - g) * (255f - ((gp shr 8) and 0xff) * k) / 255f
                    b = 255f - (255f - b) * (255f - (gp and 0xff) * k) / 255f
                }
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (l.bw) { r = lum; g = lum; b = lum }
                else if (l.sat != 1f) {
                    r = lum + (r - lum) * l.sat; g = lum + (g - lum) * l.sat; b = lum + (b - lum) * l.sat
                }
                if (l.vignette > 0f) {
                    val dx = x - cx
                    val f = 1f - l.vignette * ((dx * dx + dy * dy) / maxD2).pow(1.2f)
                    r *= f; g *= f; b *= f
                }
                if (l.grain > 0f) {
                    seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                    val n = ((seed and 0xffff) / 65535f - 0.5f) * 2f * l.grain
                    r += n; g += n; b += n
                }
                row[x] = (c and 0xff000000.toInt()) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }
}
