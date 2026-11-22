package com.autocamera.helper

import android.content.Context

class HelperPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("helper", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var look: String get() = sp.getString("look", "natural")!!; set(v) = sp.edit().putString("look", v).apply()
    /** 0 无 1 白边 2 角落小字 */
    var wmStyle: Int get() = sp.getInt("wmStyle", 1); set(v) = sp.edit().putInt("wmStyle", v).apply()
    var wmModel: Boolean get() = b("wmModel", true); set(v) = setB("wmModel", v)
    var wmParams: Boolean get() = b("wmParams", true); set(v) = setB("wmParams", v)
    var wmDate: Boolean get() = b("wmDate", true); set(v) = setB("wmDate", v)
    var displayName: String get() = sp.getString("displayName", "")!!; set(v) = sp.edit().putString("displayName", v).apply()
    var fullRes: Boolean get() = b("fullRes", false); set(v) = setB("fullRes", v)
    var auto: Boolean get() = b("auto", false); set(v) = setB("auto", v)
    var autoSince: Long get() = sp.getLong("autoSince", 0L); set(v) = sp.edit().putLong("autoSince", v).apply()
    var grid: Boolean get() = b("grid", true); set(v) = setB("grid", v)
    var level: Boolean get() = b("level", true); set(v) = setB("level", v)
    var scene: Int get() = sp.getInt("scene", 0); set(v) = sp.edit().putInt("scene", v).apply()
    var distance: Float get() = sp.getFloat("distance", 3f); set(v) = sp.edit().putFloat("distance", v).apply()
    var bubbleX: Int get() = sp.getInt("bx", -1); set(v) = sp.edit().putInt("bx", v).apply()
    var bubbleY: Int get() = sp.getInt("by", 400); set(v) = sp.edit().putInt("by", v).apply()
}
