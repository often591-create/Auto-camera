package com.autocamera.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: HelperPrefs
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private var statusMsg = ""
    private var sample: Bitmap? = null
    private val accent = Color.rgb(255, 182, 39)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private val mediaPerm get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) processUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = HelperPrefs(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(40)) }
        setContentView(ScrollView(this).apply { addView(box); fitsSystemWindows = true })
        render()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------ 画面

    private fun title(t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(accent); paint.isFakeBoldText = true; setPadding(0, dp(22), 0, dp(6))
    })

    private fun note(t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.GRAY); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(6))
    })

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = box.addView(TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(if (primary) accent else Color.argb(30, 128, 128, 128))
        setTextColor(if (primary) Color.rgb(26, 20, 4) else currentTextColor)
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

    private fun switch(t: String, v: Boolean, onSet: (Boolean) -> Unit) = box.addView(SwitchCompat(this).apply {
        text = t; textSize = 15f; isChecked = v; setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, c -> onSet(c) }
    })

    private fun render() {
        box.removeAllViews()
        box.addView(TextView(this).apply { text = "Auto Camera 助手"; textSize = 24f; paint.isFakeBoldText = true })
        note("配合手机自带的相机使用：悬浮窗给拍摄建议，滤镜和水印帮你后期。")
        status = TextView(this).apply { textSize = 13f; setTextColor(accent); text = statusMsg }
        box.addView(status)

        // 悬浮窗
        title("悬浮窗")
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            note("需要「显示在其他应用上层」权限，悬浮窗才能浮在相机上面。")
            button("去开启悬浮窗权限", true) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        } else if (OverlayService.running) {
            note("悬浮窗已开启。打开系统相机，点右侧的「AC」小圆球查看建议；圆球可以拖动。")
            button("关闭悬浮窗") { stopService(Intent(this, OverlayService::class.java)); box.postDelayed({ render() }, 300) }
        } else {
            note("开启后会显示可拖动的小圆球，以及不挡操作的网格和水平仪。")
            button("开启悬浮窗", true) {
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
                box.postDelayed({ render() }, 400)
            }
        }

        // 滤镜
        title("滤镜")
        note(Looks.byId(prefs.look).desc)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val src = sampleBitmap()
        for (l in Looks.all) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4))
                if (l.id == prefs.look) setBackgroundColor(Color.argb(70, 255, 182, 39))
                setOnClickListener { prefs.look = l.id; render() }
            }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(PhotoProcessor.preview(src, l))
            }, LinearLayout.LayoutParams(dp(78), dp(78)))
            cell.addView(TextView(this).apply {
                text = l.name; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
                if (l.id == prefs.look) { setTextColor(accent); paint.isFakeBoldText = true }
            })
            strip.addView(cell)
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        // 水印
        title("水印")
        val styles = listOf("关闭", "白边", "角落小字")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        styles.forEachIndexed { i, s ->
            row.addView(TextView(this).apply {
                text = s; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                val on = prefs.wmStyle == i
                setBackgroundColor(if (on) accent else Color.argb(30, 128, 128, 128))
                if (on) setTextColor(Color.rgb(26, 20, 4))
                setOnClickListener { prefs.wmStyle = i; render() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        box.addView(row)
        if (prefs.wmStyle != 0) {
            switch("手机型号", prefs.wmModel) { prefs.wmModel = it }
            switch("拍摄参数（焦距、光圈、快门、ISO）", prefs.wmParams) { prefs.wmParams = it }
            switch("拍摄日期", prefs.wmDate) { prefs.wmDate = it }
            box.addView(EditText(this).apply {
                hint = "显示名称（留空则用照片里的型号）"
                setText(prefs.displayName); textSize = 14f; isSingleLine = true
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { prefs.displayName = s?.toString()?.trim() ?: "" }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            })
        }

        // 处理照片
        title("处理照片")
        note("原片不会被修改，加好效果的照片另存在相册的「AutoCamera」文件夹。也可以在相册里把照片「分享」给本 App。")
        button("从相册选照片", true) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        switch("保留原始分辨率（较慢、较占内存）", prefs.fullRes) { prefs.fullRes = it }

        // 自动处理
        title("自动处理系统相机的照片")
        if (!has(mediaPerm)) {
            note("需要读取照片的权限。系统询问时请选「允许全部」，选「部分照片」会读不到新拍的照片。")
            button("允许读取照片") { permLauncher.launch(arrayOf(mediaPerm)) }
        } else {
            switch("拍完自动另存一份（滤镜 + 水印）", prefs.auto) { on ->
                prefs.auto = on
                if (on) {
                    prefs.autoSince = System.currentTimeMillis() / 1000
                    PhotoJobService.schedule(this)
                    toast("已开启：之后用系统相机拍的照片会自动处理")
                } else PhotoJobService.cancel(this)
            }
            note("只处理开启之后、相册「Camera」文件夹里的新照片。")
        }

        box.addView(TextView(this).apply {
            text = "版本 ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }}"
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(24), 0, 0)
        })
    }

    // ------------------------------------------------------------ 照片

    private fun sampleBitmap(): Bitmap {
        sample?.let { return it }
        var b: Bitmap? = null
        if (has(mediaPerm)) try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    b = if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(240, 240), null)
                    else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 }) }
                }
            }
        } catch (_: Exception) { }
        val out = b ?: Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = android.graphics.Canvas(bmp)
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, 0f, 200f, intArrayOf(Color.rgb(90, 150, 220), Color.rgb(250, 200, 140), Color.rgb(60, 90, 50)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 200f, 200f, p)
            p.shader = null; p.color = Color.rgb(200, 120, 90)
            c.drawCircle(100f, 120f, 38f, p)
        }
        sample = out
        return out
    }

    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) { i.action = null; processUris(uris) }
    }

    private fun processUris(uris: List<Uri>) {
        setStatus("处理中 0 / ${uris.size}…")
        Thread {
            val model = LensDetector.deviceNames(this).first
            var ok = 0
            for ((k, u) in uris.withIndex()) {
                try { if (PhotoProcessor.process(this, u, prefs, model) != null) ok++ } catch (_: Throwable) { }
                runOnUiThread { setStatus("处理中 ${k + 1} / ${uris.size}…") }
            }
            runOnUiThread {
                setStatus("完成：$ok 张已另存到相册的 AutoCamera 文件夹")
                toast("已处理 $ok 张")
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setStatus(t: String) { statusMsg = t; status.text = t }
}
