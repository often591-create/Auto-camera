package com.autocamera.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("autocamera", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var maxQuality: Boolean get() = b("maxQuality", true); set(v) = setB("maxQuality", v)
    var maxResolution: Boolean get() = b("maxRes", true); set(v) = setB("maxRes", v)
    var video4k: Boolean get() = b("video4k", false); set(v) = setB("video4k", v)
    var fps60: Boolean get() = b("fps60", false); set(v) = setB("fps60", v)
    var volumeShutter: Boolean get() = b("volShutter", true); set(v) = setB("volShutter", v)
    var shutterSound: Boolean get() = b("sound", true); set(v) = setB("sound", v)
    var grid: Boolean get() = b("grid", false); set(v) = setB("grid", v)
    var ratio: Int get() = sp.getInt("ratio", 0); set(v) = sp.edit().putInt("ratio", v).apply()
    var folder: String get() = sp.getString("folder", "DCIM/AutoCamera")!!; set(v) = sp.edit().putString("folder", v).apply()
}
