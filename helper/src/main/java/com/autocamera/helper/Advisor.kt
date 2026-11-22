package com.autocamera.helper

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** 依场景与距离建议倍数。给系统相机用，所以厂商隐藏的镜头也算在内。 */
object Advisor {
    val SCENES = listOf("人像", "风景", "建筑", "美食", "宠物", "夜景", "街拍", "微距")

    data class Advice(val zoom: Float, val headline: String, val tips: List<String>)

    private const val FILL = 0.75f
    private fun focalFor(d: Float, size: Float) = 36f * d / (size / FILL)
    private fun distFor(mm: Float, size: Float) = mm * (size / FILL) / 36f

    fun fmtZ(z: Float): String {
        val r = (z * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    fun fmtM(m: Float): String = when {
        m < 1f -> "${(m * 100).roundToInt()} cm"
        m < 10f -> "${(m * 10).roundToInt() / 10f} m"
        else -> "${m.roundToInt()} m"
    }

    private fun pick(stops: List<LensStop>, target: Float, low: Boolean = false): LensStop {
        var best = stops.first(); var bs = Float.MAX_VALUE
        for (s in stops) {
            var score = abs(ln(s.z / target))
            if (s.type == "crop") score += 0.12f
            if (s.z > target * 1.15f) score += 0.35f
            if (low && s.type == "tele") score += 0.6f
            if (score < bs) { bs = score; best = s }
        }
        return best
    }

    fun advise(scene: Int, dist: Float, stopsIn: List<LensStop>, macro: String?): Advice {
        val stops = stopsIn.ifEmpty { listOf(LensStop(1f, 24, "main", null, 0, "", null, true)) }
        val mainMm = (stops.firstOrNull { it.type == "main" }?.mm ?: 24).toFloat()
        val tips = mutableListOf<String>()
        fun z(l: LensStop) = "${fmtZ(l.z)}×"
        val lens: LensStop = when (scene) {
            0 -> { // 人像（半身）
                val size = 0.9f
                val l = pick(stops, focalFor(dist, size) / mainMm)
                val ideal = pick(stops, maxOf(2f, 70f / mainMm))
                if (l.mm < 45 && ideal != l) tips.add("退到约 ${fmtM(distFor(ideal.mm.toFloat(), size))} 改用 ${z(ideal)}，脸型更自然")
                tips.add("相机放在胸口到眼睛之间的高度")
                tips.add("人离背景 2 米以上，背景更虚")
                l
            }
            1 -> when {
                dist < 30f && stops.any { it.type == "uw" } -> { tips.add("放低相机，让前景占画面下方三分之一"); stops.first { it.type == "uw" } }
                dist >= 300f && stops.any { it.type == "tele" } -> { tips.add("长焦压缩距离，远山更有层次"); stops.last { it.type == "tele" } }
                else -> { tips.add("地平线放在上或下三分线，不要切在正中间"); stops.first { it.type == "main" } }
            }
            2 -> {
                val t = focalFor(dist, 25f) / mainMm
                val l = pick(stops, t)
                if (l.type == "uw") tips.add("手机保持垂直不要仰拍，避免楼往后倒")
                else tips.add("站远一点用长焦，建筑线条更直")
                tips.add("打开网格，让垂直线对齐格线")
                l
            }
            3 -> {
                tips.add("平面的菜从正上方俯拍：看水平仪，变绿再拍")
                tips.add("有高度的（汉堡、饮料）用 45° 斜拍")
                pick(stops, maxOf(1.6f, focalFor(dist, 0.35f) / mainMm))
            }
            4 -> { tips.add("蹲到宠物眼睛的高度"); tips.add("对焦在眼睛，会动就连拍"); pick(stops, focalFor(dist, 0.6f) / mainMm) }
            5 -> {
                tips.add("主摄进光最多、杂讯最少")
                tips.add("靠在栏杆或墙上稳住，夜景模式跑完再移开")
                stops.first { it.type == "main" }
            }
            6 -> { tips.add("先找好光影和背景，等路人走进画面"); pick(stops, if (dist > 8f) 2f else 1f) }
            else -> {
                val tele = stops.firstOrNull { it.type == "tele" }
                val uw = stops.firstOrNull { it.type == "uw" }
                when {
                    macro == "tele" && tele != null -> { tips.add("长焦微距：离主体约 10–30 cm"); tele }
                    macro == "uw" && uw != null -> { tips.add("靠近到 3–5 cm，相机会自动切到微距"); uw }
                    else -> { tips.add("用 2× 在约 15 cm 拍，太近会对不到焦"); pick(stops, 2f) }
                }
            }
        }
        val type = when (lens.type) { "uw" -> "超广角"; "main" -> "主镜头"; "crop" -> "裁切"; else -> "长焦" }
        return Advice(lens.z, "用 ${z(lens)}（${lens.mm}mm $type）", tips)
    }
}
