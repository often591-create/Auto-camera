package com.autocamera.helper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** 浮在系统相机上方的小圆球与建议面板，加上不挡触控的网格和水平仪。 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        var running = false
        const val ACTION_STOP = "stop"
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: HelperPrefs
    private var bubble: TextView? = null
    private var panel: View? = null
    private var guide: GuideView? = null
    private var levelText: TextView? = null
    private var adviceText: TextView? = null
    private var tipsText: TextView? = null
    private var report: LensReport? = null
    private var sensors: SensorManager? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val overlayType get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = HelperPrefs(this)
        startAsForeground()
        running = true
        Thread { val r = try { LensDetector.detect(this) } catch (e: Exception) { null }; bubble?.post { report = r; refreshAdvice() } }.start()
        addGuide()
        addBubble()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val s = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        sensors?.unregisterListener(this)
        listOf(bubble, panel, guide).forEach { v -> if (v != null) try { wm.removeView(v) } catch (_: Exception) { } }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("overlay", "悬浮窗", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("拍摄助手悬浮窗已开启")
            .setContentText("打开系统相机，点小圆球查看建议")
            .setContentIntent(open)
            .addAction(0, "关闭悬浮窗", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 4301, n, type)
    }

    // ------------------------------------------------------------ 网格与水平仪（不挡触控）

    private fun addGuide() {
        val v = GuideView(this)
        v.showGrid = prefs.grid
        v.showLevel = prefs.level
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.alpha = 0.8f // 安卓 12 以后，透明度 0.8 以下的悬浮层才不会挡住下面 App 的触控
        wm.addView(v, lp)
        guide = v
    }

    // ------------------------------------------------------------ 小圆球

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(52)
        val b = TextView(this).apply {
            text = "AC"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 182, 39))
            setBackgroundResource(R.drawable.bg_bubble)
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val sw = resources.displayMetrics.widthPixels
        lp.x = if (prefs.bubbleX < 0) sw - size - dp(8) else prefs.bubbleX
        lp.y = prefs.bubbleY
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = startX + dx.roundToInt(); lp.y = startY + dy.roundToInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y } else togglePanel(lp)
                }
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    // ------------------------------------------------------------ 建议面板

    private fun togglePanel(bubbleLp: WindowManager.LayoutParams) {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { }; panel = null; levelText = null; return }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "拍摄建议"; textSize = 15f; setTextColor(Color.WHITE); paint.isFakeBoldText = true },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.LTGRAY); setPadding(dp(10), 0, 0, 0)
            setOnClickListener { togglePanel(bubbleLp) }
        })
        card.addView(head)

        val lensRow = TextView(this).apply { textSize = 11f; setTextColor(Color.GRAY); setPadding(0, dp(2), 0, dp(6)) }
        report?.let { r -> lensRow.text = "你的镜头：" + r.stops.joinToString("  ") { Advisor.fmtZ(it.z) + "×" } }
        card.addView(lensRow)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawChips() {
            for (i in 0 until chips.childCount) {
                val c = chips.getChildAt(i) as TextView
                val on = i == prefs.scene
                c.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                c.setTextColor(if (on) Color.rgb(26, 20, 4) else Color.WHITE)
            }
        }
        Advisor.SCENES.forEachIndexed { i, name ->
            chips.addView(TextView(this).apply {
                text = name; textSize = 13f; setPadding(dp(12), dp(5), dp(12), dp(5))
                setOnClickListener { prefs.scene = i; drawChips(); refreshAdvice() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        drawChips()
        card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val distLabel = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        fun distToProgress(m: Float) = (1000 * ln10(m / 0.05f) / ln10(2000f / 0.05f)).roundToInt()
        fun progressToDist(p: Int) = 0.05f * (2000f / 0.05f).pow(p / 1000f)
        distLabel.text = "和主体的距离：${Advisor.fmtM(prefs.distance)}"
        card.addView(distLabel)
        card.addView(SeekBar(this).apply {
            max = 1000; progress = distToProgress(prefs.distance)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.distance = progressToDist(p); distLabel.text = "和主体的距离：${Advisor.fmtM(prefs.distance)}"; refreshAdvice()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        adviceText = TextView(this).apply { textSize = 20f; setTextColor(Color.rgb(255, 182, 39)); paint.isFakeBoldText = true; setPadding(0, dp(4), 0, dp(2)) }
        tipsText = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.25f) }
        card.addView(adviceText); card.addView(tipsText)

        levelText = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        card.addView(levelText)

        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val t = TextView(this).apply { text = label; textSize = 13f; setPadding(dp(12), dp(5), dp(12), dp(5)) }
            fun paint() { val on = get(); t.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip); t.setTextColor(if (on) Color.rgb(26, 20, 4) else Color.WHITE) }
            paint()
            t.setOnClickListener { set(!get()); paint() }
            toggles.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        toggle("网格", { prefs.grid }) { prefs.grid = it; guide?.showGrid = it; guide?.invalidate() }
        toggle("水平仪", { prefs.level }) { prefs.level = it; guide?.showLevel = it; guide?.invalidate() }
        card.addView(toggles)

        val lp = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val sw = resources.displayMetrics.widthPixels
        lp.x = (sw - dp(300)) / 2
        lp.y = (bubbleLp.y + dp(60)).coerceAtMost(resources.displayMetrics.heightPixels / 2)
        wm.addView(card, lp)
        panel = card
        refreshAdvice()
    }

    private fun ln10(x: Float) = kotlin.math.ln(x)

    private fun refreshAdvice() {
        val a = Advisor.advise(prefs.scene, prefs.distance, report?.stops ?: emptyList(), report?.macro)
        adviceText?.text = a.headline
        tipsText?.text = a.tips.joinToString("\n") { "· $it" }
    }

    // ------------------------------------------------------------ 感应器

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
        val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        val flat = abs(gz) / g > 0.9f
        val g2 = guide ?: return
        if (flat) {
            val tilt = Math.toDegrees(asin((hypot(gx, gy) / g).toDouble().coerceAtMost(1.0))).toFloat()
            g2.flat = true; g2.angle = tilt
            levelText?.text = if (tilt < 1.5f) "俯拍水平 ✓" else "俯拍：还差 ${tilt.roundToInt()}°"
        } else {
            var roll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            val snap = (roll / 90f).roundToInt() * 90f
            roll -= snap
            g2.flat = false; g2.angle = roll; g2.screenSnap = snap
            levelText?.text = if (abs(roll) < 1f) "水平 ✓" else "水平：歪了 ${abs(roll).roundToInt()}°，往${if (roll > 0) "右" else "左"}转一点"
        }
        g2.invalidate()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** 全屏、不接受触控的绘图层。 */
    class GuideView(ctx: Context) : View(ctx) {
        var showGrid = true
        var showLevel = true
        var angle = 0f
        var flat = false
        var screenSnap = 0f
        private val d = resources.displayMetrics.density
        private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); strokeWidth = d }
        private val lvl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f * d; style = Paint.Style.STROKE }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (showGrid) for (k in 1..2) {
                c.drawLine(w * k / 3, 0f, w * k / 3, h, gridP)
                c.drawLine(0f, h * k / 3, w, h * k / 3, gridP)
            }
            if (!showLevel) return
            val ok = if (flat) angle < 1.5f else abs(angle) < 1f
            lvl.color = if (ok) Color.rgb(95, 211, 141) else Color.rgb(255, 182, 39)
            val cx = w / 2; val cy = h / 2
            if (flat) {
                c.drawCircle(cx, cy, 28 * d, lvl)
                val off = (angle * 3 * d).coerceAtMost(60 * d)
                c.drawCircle(cx, cy - off, 10 * d, lvl)
            } else {
                c.save()
                c.rotate(-angle - screenSnap, cx, cy)
                c.drawLine(cx - 70 * d, cy, cx - 20 * d, cy, lvl)
                c.drawLine(cx + 20 * d, cy, cx + 70 * d, cy, lvl)
                c.restore()
                c.save()
                c.rotate(-screenSnap, cx, cy)
                c.drawLine(cx - 16 * d, cy, cx + 16 * d, cy, lvl)
                c.restore()
            }
        }
    }
}
