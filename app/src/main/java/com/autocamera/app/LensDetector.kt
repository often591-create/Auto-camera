package com.autocamera.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 一个倍数按钮。
 * cameraId == null：用默认后摄的变焦倍率 z 切换；否则要切到那颗独立的相机 ID。
 * available == false：手机有这颗镜头，但厂商没开放给第三方 App。
 */
data class LensStop(
    val z: Float,
    val mm: Int,
    val type: String,      // uw / main / crop / tele
    val aperture: Float?,
    val mp: Int,
    val source: String,    // 硬件 / 数据库 / 推算
    val cameraId: String?,
    val available: Boolean
)

data class LensReport(
    val deviceName: String,
    val dbName: String?,
    val macro: String?,
    val stops: List<LensStop>,
    val summary: String,
    val raw: List<String>
)

object LensDetector {

    private class Cam(
        val id: String, val parent: String?, val listed: Boolean, val back: Boolean, val logical: Boolean,
        val focal: Float?, val sw: Float, val sh: Float, val mp: Int, val ap: Float?, val zMin: Float, val zMax: Float
    )

    private class Ln(val cam: Cam, val eq: Float, val diag: Float)

    private val SNAP = floatArrayOf(.5f, .6f, .7f, 2f, 2.5f, 3f, 3.2f, 3.5f, 3.7f, 4f, 4.3f, 5f, 6f, 10f)

    private fun snap(z: Float): Float {
        for (s in SNAP) if (abs(s - z) / s < 0.07f) return s
        return (z * 10).roundToInt() / 10f
    }

    private fun getprop(key: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val s = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        s.ifBlank { null }
    } catch (e: Exception) { null }

    /** 型号代码 + 各厂商的市场名称，国内版、国际版都适用。 */
    fun deviceNames(ctx: Context): Pair<String, List<String>> {
        val names = mutableListOf(Build.MODEL, "${Build.MANUFACTURER} ${Build.MODEL}")
        var market: String? = null
        for (k in listOf(
            "ro.product.marketname", "ro.product.vendor.marketname", "ro.product.odm.marketname",
            "ro.vendor.oplus.market.name", "ro.oppo.market.name", "ro.vivo.market.name", "ro.config.marketing_name"
        )) {
            getprop(k)?.let { names.add(it); if (market == null) market = it }
        }
        try {
            Settings.Global.getString(ctx.contentResolver, "device_name")?.let { if (it.isNotBlank()) names.add(it) }
        } catch (_: Exception) { }
        val display = market ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        return display to names.distinct()
    }

    fun detect(ctx: Context): LensReport {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val listed = try { cm.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        val cams = mutableListOf<Cam>()
        val seen = HashSet<String>()

        fun add(id: String, parent: String?) {
            if (!seen.add(id)) return
            val c = try { cm.getCameraCharacteristics(id) } catch (e: Exception) { return }
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.minOrNull()
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val logical = Build.VERSION.SDK_INT >= 28 &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            var zMin = 1f
            var zMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { zMin = it.lower; zMax = it.upper }
            }
            val mp = if (px != null) ((px.width.toLong() * px.height) / 1_000_000L).toInt() else 0
            cams.add(
                Cam(id, parent, id in listed, facing == CameraCharacteristics.LENS_FACING_BACK, logical,
                    fl, size?.width ?: 0f, size?.height ?: 0f, mp, ap, zMin, zMax)
            )
            if (logical && Build.VERSION.SDK_INT >= 28) {
                for (p in c.physicalCameraIds) add(p, id)
            }
        }

        listed.forEach { add(it, null) }
        for (i in 0 until 20) { val s = i.toString(); if (s !in listed) add(s, null) }

        val (display, names) = deviceNames(ctx)
        val db = PhoneDb.match(names)

        val defaultBackId = listed.firstOrNull { id -> cams.any { it.id == id && it.back } }
        val defCam = cams.firstOrNull { it.id == defaultBackId }
        val zMin = defCam?.zMin ?: 1f
        val zMax = defCam?.zMax ?: 1f
        fun reachable(z: Float) = z >= zMin - 0.03f && z <= zMax + 0.05f

        val back = cams.filter { it.back && it.focal != null && it.sw > 0f }
        val phys = back.filter { !it.logical }
        val src = if (phys.isNotEmpty()) phys else back
        val lns = src.map { val d = hypot(it.sw, it.sh); Ln(it, it.focal!! * 43.27f / d, d) }.sortedBy { it.eq }
        val uniq = mutableListOf<Ln>()
        for (l in lns) {
            val i = uniq.indexOfFirst { abs(it.eq - l.eq) / it.eq < 0.06f }
            if (i < 0) uniq.add(l)
            else {
                val o = uniq[i]
                val better = (l.cam.listed && !o.cam.listed) || (l.cam.listed == o.cam.listed && l.cam.mp > o.cam.mp)
                if (better) uniq[i] = l
            }
        }

        val stops = mutableListOf<LensStop>()
        if (uniq.isNotEmpty()) {
            val main = uniq.filter { it.eq in 20f..32f }.maxByOrNull { it.diag }
                ?: uniq.minByOrNull { abs(it.eq - 25f) }!!
            for (l in uniq) {
                val z = if (l === main) 1f else snap(l.eq / main.eq)
                val type = when { l === main -> "main"; z < 0.9f -> "uw"; else -> "tele" }
                val reach = reachable(z)
                val ownId = if (!reach && l.cam.listed && l.cam.id != defaultBackId) l.cam.id else null
                stops.add(LensStop(z, l.eq.roundToInt(), type, l.cam.ap, l.cam.mp, "硬件", ownId, reach || ownId != null))
            }
            if (main.cam.mp >= 40 && stops.none { it.z in 1.6f..2.4f }) {
                stops.add(LensStop(2f, (main.eq * 2).roundToInt(), "crop", main.cam.ap, 0, "裁切", null, reachable(2f)))
            }
        }

        // 高像素长焦：加一个 2 倍无损裁切（例如 2.5× 长焦 → 5×）
        val teleHi = stops.filter { it.type == "tele" && it.mp >= 40 && it.available }.maxByOrNull { it.z }
        if (teleHi != null) {
            val cz = snap(teleHi.z * 2f)
            if (stops.none { abs(it.z - cz) / cz < 0.12f }) {
                val ok = teleHi.cameraId != null || reachable(cz)
                stops.add(LensStop(cz, teleHi.mm * 2, "crop", teleHi.aperture, 0, "长焦裁切", teleHi.cameraId, ok))
            }
        }

        var dbAdded = 0
        if (db != null) {
            for (d in db.lenses) {
                if (stops.any { abs(it.z - d.z) / d.z < 0.12f }) continue
                stops.add(LensStop(d.z, d.mm, d.type, d.f, 0, "数据库", null, reachable(d.z)))
                dbAdded++
            }
        }
        if (stops.none { it.type == "main" }) {
            stops.add(LensStop(1f, 25, "main", null, 0, "推算", null, true))
        }
        if (stops.none { it.type == "uw" } && zMin < 0.95f) {
            stops.add(LensStop((zMin * 10).roundToInt() / 10f, (25 * zMin).roundToInt(), "uw", null, 0, "推算", null, true))
        }
        if (stops.size <= 1) {
            for (z in listOf(2f, 5f)) if (z <= zMax) stops.add(LensStop(z, (25 * z).roundToInt(), "crop", null, 0, "推算", null, true))
        }
        stops.sortBy { it.z }

        val hwCount = uniq.size
        val summary = buildString {
            append(display)
            append("：系统开放了 $hwCount 颗后镜头")
            if (db != null) append("，机型库对应「${db.name}」")
            if (dbAdded > 0) append("，另有 $dbAdded 颗被厂商隐藏，已用机型库补上")
            val locked = stops.count { !it.available }
            if (locked > 0) append("。其中 $locked 颗厂商不允许第三方 App 使用")
            append("。")
        }

        val raw = cams.filter { it.back }.map { c ->
            val eq = if (c.focal != null && c.sw > 0f) (c.focal * 43.27f / hypot(c.sw, c.sh)).roundToInt().toString() else "?"
            "ID ${c.id}" + (if (c.parent != null) "（属于 ${c.parent}）" else "") +
                (if (c.logical) " 逻辑镜头" else "") + (if (!c.listed) " 未公开" else "") +
                "：f=${c.focal?.let { "%.2f".format(it) } ?: "?"}mm → 等效 ${eq}mm" +
                (if (c.sw > 0f) "，传感器 %.1f×%.1fmm".format(c.sw, c.sh) else "") +
                (if (c.mp > 0) "，${c.mp}MP" else "") +
                (c.ap?.let { "，f/%.2f".format(it) } ?: "") +
                "，变焦 %.2f–%.0f×".format(c.zMin, c.zMax)
        }
        return LensReport(display, db?.name, db?.macro, stops, summary, raw)
    }
}
