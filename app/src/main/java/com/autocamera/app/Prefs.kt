package com.autocamera.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("autocamera", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var maxQuality: Boolean get() = b("maxQuality", true); set(v) = setB("maxQuality", v)
    var maxResolution: Boolean get() = b("maxRes", false); set(v) = setB("maxRes", v)
    var videoQuality: String get() = sp.getString("videoQuality", "FHD")!!; set(v) = sp.edit().putString("videoQuality", v).apply()
    var videoFps: Int get() = sp.getInt("videoFps", 30); set(v) = sp.edit().putInt("videoFps", v).apply()
    var videoHdr: Boolean get() = b("videoHdr", false); set(v) = setB("videoHdr", v)
    var videoStab: Boolean get() = b("videoStab", true); set(v) = setB("videoStab", v)
    var volumeShutter: Boolean get() = b("volShutter", true); set(v) = setB("volShutter", v)
    var shutterSound: Boolean get() = b("sound", true); set(v) = setB("sound", v)
    var autoHdr: Boolean get() = b("autoHdr", true); set(v) = setB("autoHdr", v)
    var pinnedModes: String get() = sp.getString("pinned", "PHOTO,PORTRAIT,VIDEO,PRO")!!; set(v) = sp.edit().putString("pinned", v).apply()
    var grid: Boolean get() = b("grid", false); set(v) = setB("grid", v)
    var ratio: Int get() = sp.getInt("ratio", 0); set(v) = sp.edit().putInt("ratio", v).apply()
    var folder: String get() = sp.getString("folder", "DCIM/AutoCamera")!!; set(v) = sp.edit().putString("folder", v).apply()
}
