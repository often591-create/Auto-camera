package com.autocamera.app

/** 机型镜头资料库（只收录安卓）。硬件读取不到的镜头才会用这里的资料补上。 */
data class DbLens(val z: Float, val mm: Int, val type: String, val f: Float, val note: String = "")

data class DbPhone(
    val name: String,
    val patterns: List<Regex>,
    val lenses: List<DbLens>,
    val macro: String? // "uw" 超广角微距 / "tele" 长焦微距 / null
)

object PhoneDb {
    private fun r(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }
    private fun l(z: Float, mm: Int, t: String, f: Float, note: String = "") = DbLens(z, mm, t, f, note)

    // 型号同时比对 Build.MODEL（如 SM-S9380、25010PN30C）与厂商的市场名称（如 Xiaomi 15 Ultra），
    // 所以国内版、国际版都能认出来。
    val phones = listOf(
        DbPhone("Samsung Galaxy S25 Ultra", r("SM-S938", "S25 Ultra"),
            listOf(l(.6f, 13, "uw", 1.9f), l(1f, 23, "main", 1.7f), l(2f, 46, "crop", 1.7f), l(3f, 67, "tele", 2.4f), l(5f, 111, "tele", 3.4f), l(10f, 230, "crop", 3.4f)), "uw"),
        DbPhone("Samsung Galaxy S24 Ultra", r("SM-S928", "S24 Ultra"),
            listOf(l(.6f, 13, "uw", 2.2f), l(1f, 23, "main", 1.7f), l(2f, 46, "crop", 1.7f), l(3f, 67, "tele", 2.4f), l(5f, 111, "tele", 3.4f), l(10f, 230, "crop", 3.4f)), null),
        DbPhone("Samsung Galaxy S24 / S25（含 +）", r("SM-S92[16]", "SM-S93[16]", "Galaxy S2[45]\\+?$"),
            listOf(l(.6f, 13, "uw", 2.2f), l(1f, 24, "main", 1.8f), l(2f, 48, "crop", 1.8f), l(3f, 67, "tele", 2.4f)), null),
        DbPhone("Google Pixel 9 Pro / 10 Pro", r("Pixel (9|10) Pro"),
            listOf(l(.5f, 13, "uw", 1.7f), l(1f, 25, "main", 1.68f), l(2f, 50, "crop", 1.68f), l(5f, 113, "tele", 2.8f)), "uw"),
        DbPhone("Google Pixel 10", r("^Pixel 10$"),
            listOf(l(.5f, 13, "uw", 2.2f), l(1f, 25, "main", 1.7f), l(2f, 50, "crop", 1.7f), l(5f, 113, "tele", 3.1f)), "uw"),
        DbPhone("Google Pixel 9", r("^Pixel 9$"),
            listOf(l(.5f, 13, "uw", 1.7f), l(1f, 25, "main", 1.68f), l(2f, 50, "crop", 1.68f)), "uw"),
        DbPhone("小米 15 Ultra", r("25010PN30", "(Xiaomi|小米) ?15 Ultra"),
            listOf(l(.6f, 14, "uw", 2.2f), l(1f, 23, "main", 1.63f, "一英寸大底"), l(3f, 70, "tele", 1.8f), l(4.3f, 100, "tele", 2.6f)), "tele"),
        DbPhone("小米 14 Ultra", r("24030PN60", "(Xiaomi|小米) ?14 Ultra"),
            listOf(l(.5f, 12, "uw", 1.8f), l(1f, 23, "main", 1.63f, "一英寸大底"), l(3.2f, 75, "tele", 1.8f), l(5f, 120, "tele", 2.5f)), "tele"),
        DbPhone("vivo X200 Pro", r("X200 Pro(?! ?mini)"),
            listOf(l(.6f, 15, "uw", 2.0f), l(1f, 24, "main", 1.57f), l(2f, 48, "crop", 1.57f), l(3.7f, 85, "tele", 2.67f)), "tele"),
        DbPhone("vivo X100 Pro", r("X100 Pro"),
            listOf(l(.6f, 15, "uw", 2.0f), l(1f, 23, "main", 1.75f, "一英寸大底"), l(2f, 46, "crop", 1.75f), l(4.3f, 100, "tele", 2.5f)), "tele"),
        DbPhone("OPPO Find X8 Ultra", r("Find X8 Ultra", "PKJ110"),
            listOf(l(.6f, 15, "uw", 2.0f), l(1f, 23, "main", 1.8f, "一英寸大底"), l(3f, 70, "tele", 2.1f), l(6f, 135, "tele", 3.1f)), "tele"),
        DbPhone("OPPO Find X8 Pro", r("Find X8 Pro", "PKC110"),
            listOf(l(.6f, 15, "uw", 2.0f), l(1f, 24, "main", 1.6f), l(3f, 73, "tele", 2.6f), l(6f, 135, "tele", 4.3f)), "tele"),
        DbPhone("华为 Pura 70 Ultra", r("Pura 70 Ultra", "^HBP-"),
            listOf(l(.5f, 13, "uw", 2.2f), l(1f, 23, "main", 1.6f, "一英寸大底"), l(3.5f, 90, "tele", 2.1f)), "tele"),
        DbPhone("华为 Mate 60 / 70 Pro 系列", r("Mate ?(60|70) Pro", "^ALN-"),
            listOf(l(.5f, 13, "uw", 2.2f), l(1f, 24, "main", 1.4f, "可变光圈"), l(3.5f, 90, "tele", 2.1f)), "tele"),
        DbPhone("荣耀 Magic7 Pro", r("Magic ?7 Pro", "^PTP-"),
            listOf(l(.5f, 13, "uw", 2.0f), l(1f, 23, "main", 1.4f, "可变光圈"), l(3f, 70, "tele", 2.6f)), "tele"),
        DbPhone("OnePlus 13", r("OnePlus ?13(?![RTSrts])", "PJZ110", "CPH2649", "CPH2653"),
            listOf(l(.6f, 15, "uw", 2.0f), l(1f, 23, "main", 1.6f), l(3f, 73, "tele", 2.6f)), "tele"),
    )

    fun match(names: List<String>): DbPhone? {
        for (p in phones) for (n in names) if (p.patterns.any { it.containsMatchIn(n) }) return p
        return null
    }
}
