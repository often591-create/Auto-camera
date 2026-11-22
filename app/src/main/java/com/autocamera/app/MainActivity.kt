package com.autocamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    enum class Mode(val label: String) { PHOTO("拍照"), PORTRAIT("人像"), NIGHT("夜景"), VIDEO("录像"), PRO("专业") }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var frame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var flashView: View
    private lateinit var topBar: LinearLayout
    private lateinit var btnFlash: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnRatio: TextView
    private lateinit var btnGrid: TextView
    private lateinit var recTime: TextView
    private lateinit var countdown: TextView
    private lateinit var proInfo: TextView
    private lateinit var proPanel: LinearLayout
    private lateinit var exposureRow: LinearLayout
    private lateinit var exposureSeek: SeekBar
    private lateinit var exposureVal: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeBar: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var shutter: View
    private lateinit var btnSwitch: TextView

    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var report: LensReport? = null

    private var mode = Mode.PHOTO
    private var facing = CameraSelector.LENS_FACING_BACK
    private var boundCameraId: String? = null   // null = 默认后摄（用变焦倍率切镜头）
    private var pendingZoom = 1f
    private var flash = 0                       // 0 自动 1 开 2 关 3 常亮
    private var timerSec = 0
    private var rotation = Surface.ROTATION_0
    private var lastUri: Uri? = null
    private var lastIsVideo = false
    private var counting = false
    private var bursting = false
    private var burstCount = 0
    private var defaultZoomMin = 1f
    private var defaultZoomMax = 1f
    private var fps60Fallback = false

    // 实时拍摄参数（专业模式显示，第二阶段测距也会用到）
    @Volatile private var liveIso: Int? = null
    @Volatile private var liveExposure: Long? = null
    @Volatile private var liveFocus: Float? = null

    // 专业模式设定（null / 0 = 自动）
    private var proIso: Int? = null
    private var proExposure: Long? = null
    private var proWb = 0
    private var proFocus: Float? = null

    private val sound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera()
        else showPermissionNeeded()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        bindViews()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        overlay.grid = prefs.grid
        setupTopBar()
        setupGestures()
        setupShutter()
        btnSwitch.setOnClickListener { switchFacing() }
        thumb.setOnClickListener { openLast() }

        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != rotation) {
                    rotation = r
                    imageCapture?.targetRotation = r
                    videoCapture?.targetRotation = r
                }
            }
        }.enable()

        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.all { has(it) }) startCamera() else permLauncher.launch(need.toTypedArray())
    }

    override fun onStop() {
        super.onStop()
        recording?.stop()
        recording = null
        bursting = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sound.release() } catch (_: Exception) { }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.volumeShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        frame = findViewById(R.id.frame)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        flashView = findViewById(R.id.flashView)
        topBar = findViewById(R.id.topBar)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnRatio = findViewById(R.id.btnRatio)
        btnGrid = findViewById(R.id.btnGrid)
        recTime = findViewById(R.id.recTime)
        countdown = findViewById(R.id.countdown)
        proInfo = findViewById(R.id.proInfo)
        proPanel = findViewById(R.id.proPanel)
        exposureRow = findViewById(R.id.exposureRow)
        exposureSeek = findViewById(R.id.exposureSeek)
        exposureVal = findViewById(R.id.exposureVal)
        zoomRow = findViewById(R.id.zoomRow)
        modeBar = findViewById(R.id.modeBar)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private fun showPermissionNeeded() {
        AlertDialog.Builder(this, R.style.Dialog_AutoCamera)
            .setTitle("需要相机权限")
            .setMessage("Auto Camera 需要使用相机才能拍照。请在系统设置里允许相机权限，录像还需要麦克风权限。")
            .setPositiveButton("重试") { _, _ -> permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------- 启动相机

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = try { f.get() } catch (e: Exception) { toast("相机启动失败：${e.message}"); return@addListener }
            provider = p
            Thread {
                val r = try { LensDetector.detect(this) } catch (e: Exception) { null }
                main.post {
                    report = r
                    val ef = ExtensionsManager.getInstanceAsync(this, p)
                    ef.addListener({
                        extensions = try { ef.get() } catch (e: Exception) { null }
                        buildModeBar()
                        rebind()
                    }, ContextCompat.getMainExecutor(this))
                }
            }.start()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun baseSelector(): CameraSelector {
        val id = boundCameraId
        val b = CameraSelector.Builder().requireLensFacing(facing)
        if (id != null && facing == CameraSelector.LENS_FACING_BACK && mode != Mode.PORTRAIT && mode != Mode.NIGHT) {
            b.addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        }
        return b.build()
    }

    private fun extensionFor(m: Mode) = when (m) {
        Mode.PORTRAIT -> ExtensionMode.BOKEH
        Mode.NIGHT -> ExtensionMode.NIGHT
        else -> null
    }

    private fun modeAvailable(m: Mode): Boolean {
        val ext = extensionFor(m) ?: return true
        val em = extensions ?: return false
        val sel = CameraSelector.Builder().requireLensFacing(facing).build()
        return try { em.isExtensionAvailable(sel, ext) } catch (e: Exception) { false }
    }

    /** 依画面比例调整取景框大小，然后重新绑定相机。 */
    private fun rebind() {
        val w = root.width
        if (w == 0) { root.post { rebind() }; return }
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        when (prefs.ratio) {
            0 -> { lp.width = w; lp.height = w * 4 / 3 }
            1 -> { lp.width = w; lp.height = w * 16 / 9 }
            2 -> { lp.width = w; lp.height = w }
            else -> { lp.width = FrameLayout.LayoutParams.MATCH_PARENT; lp.height = FrameLayout.LayoutParams.MATCH_PARENT }
        }
        lp.gravity = Gravity.CENTER
        frame.layoutParams = lp
        btnRatio.text = arrayOf("4:3", "16:9", "1:1", "全屏")[prefs.ratio]
        frame.post { bindUseCases() }
    }

    private fun bindUseCases() {
        val p = provider ?: return
        p.unbindAll()
        camera = null; imageCapture = null; videoCapture = null

        var selector = baseSelector()
        val ext = extensionFor(mode)
        if (ext != null) {
            val em = extensions
            if (em != null && modeAvailable(mode)) {
                selector = em.getExtensionEnabledCameraSelector(CameraSelector.Builder().requireLensFacing(facing).build(), ext)
            } else {
                toast("这支手机没有开放「${mode.label}」模式给第三方 App")
                mode = Mode.PHOTO; buildModeBar()
            }
        }

        val aspect = if (prefs.ratio == 1 || prefs.ratio == 3) androidx.camera.core.AspectRatio.RATIO_16_9 else androidx.camera.core.AspectRatio.RATIO_4_3
        val aspectStrategy = AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                liveIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                liveExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                liveFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            }
        })
        val preview = previewBuilder.build()

        val viewPort = previewView.viewPort
        val group = UseCaseGroup.Builder().addUseCase(preview)
        if (viewPort != null) group.setViewPort(viewPort)

        if (mode == Mode.VIDEO) {
            val q = if (prefs.video4k) Quality.UHD else Quality.FHD
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            if (prefs.fps60 && !fps60Fallback) vb.setTargetFrameRate(Range(60, 60))
            val vc = vb.build()
            videoCapture = vc
            group.addUseCase(vc)
        } else {
            val resBuilder = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
            if (prefs.maxResolution) resBuilder.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            val ic = ImageCapture.Builder()
                .setCaptureMode(if (prefs.maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resBuilder.build())
                .setTargetRotation(rotation)
                .build()
            imageCapture = ic
            group.addUseCase(ic)
        }

        try {
            camera = p.bindToLifecycle(this, selector, group.build())
        } catch (e: Exception) {
            if (mode == Mode.VIDEO && prefs.fps60 && !fps60Fallback) {
                fps60Fallback = true
                toast("这支手机不支持 60 fps，已改为 30 fps")
                bindUseCases(); return
            }
            if (boundCameraId != null) {
                boundCameraId = null; pendingZoom = 1f
                toast("无法切换到这颗镜头，已回到主镜头")
                bindUseCases(); return
            }
            toast("相机启动失败：${e.message}")
            return
        }
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val cam = camera ?: return
        cam.cameraInfo.zoomState.removeObservers(this)
        cam.cameraInfo.zoomState.observe(this) { zs -> updateZoomUi(zs.zoomRatio) }
        if (boundCameraId == null && facing == CameraSelector.LENS_FACING_BACK && ext == null) {
            cam.cameraInfo.zoomState.value?.let { defaultZoomMin = it.minZoomRatio; defaultZoomMax = it.maxZoomRatio }
        }
        if (pendingZoom != 1f) {
            cam.cameraControl.setZoomRatio(pendingZoom)
            pendingZoom = 1f
        }
        btnFlash.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
        applyFlash()
        buildZoomRow()
        setupExposure()
        buildProPanel()
        applyPro()
        updateShutterIcon()
    }

    // ---------------------------------------------------------------- 顶部按钮

    private fun setupTopBar() {
        btnFlash.setOnClickListener {
            flash = (flash + 1) % 4
            applyFlash()
        }
        btnTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            btnTimer.text = if (timerSec == 0) "定时 关" else "定时 ${timerSec}s"
            btnTimer.setTextColor(if (timerSec == 0) Color.WHITE else Color.rgb(255, 182, 39))
        }
        btnRatio.setOnClickListener {
            if (recording != null) return@setOnClickListener
            prefs.ratio = (prefs.ratio + 1) % 4
            rebind()
        }
        btnGrid.setOnClickListener {
            prefs.grid = !prefs.grid
            overlay.grid = prefs.grid
            btnGrid.setTextColor(if (prefs.grid) Color.rgb(255, 182, 39) else Color.WHITE)
        }
        btnGrid.setTextColor(if (prefs.grid) Color.rgb(255, 182, 39) else Color.WHITE)
        findViewById<TextView>(R.id.btnSettings).setOnClickListener { showSettings() }
    }

    private fun applyFlash() {
        btnFlash.text = arrayOf("闪光 自动", "闪光 开", "闪光 关", "常亮")[flash]
        btnFlash.setTextColor(if (flash == 2) Color.WHITE else Color.rgb(255, 182, 39))
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(flash == 3)
        imageCapture?.flashMode = when (flash) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // ---------------------------------------------------------------- 模式

    private fun buildModeBar() {
        modeBar.removeAllViews()
        for (m in Mode.values()) {
            if (!modeAvailable(m)) continue
            val t = TextView(this).apply {
                text = m.label
                textSize = 15f
                setPadding(dp(14), 0, dp(14), 0)
                gravity = Gravity.CENTER
                setTextColor(if (m == mode) Color.rgb(255, 182, 39) else Color.WHITE)
                paint.isFakeBoldText = m == mode
                setOnClickListener { setMode(m) }
            }
            modeBar.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun setMode(m: Mode) {
        if (m == mode || recording != null || counting) return
        mode = m
        if (m == Mode.PORTRAIT || m == Mode.NIGHT) boundCameraId = null
        buildModeBar()
        rebind()
    }

    private fun switchFacing() {
        if (recording != null || counting) return
        facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        boundCameraId = null; pendingZoom = 1f
        if (!modeAvailable(mode)) mode = Mode.PHOTO
        buildModeBar()
        rebind()
    }

    // ---------------------------------------------------------------- 倍数按钮

    private fun zoomStops(): List<LensStop> {
        val r = report
        if (facing != CameraSelector.LENS_FACING_BACK || r == null) return listOf(LensStop(1f, 0, "main", null, 0, "", null, true))
        val ext = extensionFor(mode) != null
        return r.stops.filter { s ->
            when {
                ext -> s.cameraId == null && s.z >= defaultZoomMin - 0.03f && s.z <= defaultZoomMax + 0.05f
                else -> true
            }
        }
    }

    private fun buildZoomRow() {
        zoomRow.removeAllViews()
        for (s in zoomStops()) {
            val t = TextView(this).apply {
                text = fmtZ(s.z)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_round)
                alpha = if (s.available) 1f else 0.35f
                tag = s
                setOnClickListener { onZoomStop(s) }
                setOnLongClickListener { showLensInfo(); true }
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(5); marginEnd = dp(5) }
            zoomRow.addView(t, lp)
        }
        camera?.cameraInfo?.zoomState?.value?.let { updateZoomUi(it.zoomRatio) }
    }

    private fun onZoomStop(s: LensStop) {
        if (!s.available) {
            toast("${fmtZ(s.z)}×（${s.mm}mm）厂商没有开放给第三方 App，只能在系统相机里使用")
            return
        }
        val cam = camera ?: return
        if (s.cameraId != boundCameraId) {
            if (recording != null) { toast("录像中无法切换这颗镜头"); return }
            boundCameraId = s.cameraId
            pendingZoom = if (s.cameraId == null) s.z else 1f
            bindUseCases()
            return
        }
        if (s.cameraId == null) cam.cameraControl.setZoomRatio(s.z)
        else cam.cameraControl.setZoomRatio(1f)
    }

    private fun currentEffectiveZoom(ratio: Float): Float {
        val id = boundCameraId ?: return ratio
        val s = report?.stops?.firstOrNull { it.cameraId == id } ?: return ratio
        return s.z * ratio
    }

    private fun updateZoomUi(ratio: Float) {
        val eff = currentEffectiveZoom(ratio)
        var best: TextView? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView
            val s = v.tag as LensStop
            if (!s.available) continue
            val d = abs(s.z - eff) + (if (s.z > eff + 0.05f) 100f else 0f)
            if (d < bestD) { bestD = d; best = v }
        }
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView
            val s = v.tag as LensStop
            if (v === best) {
                v.setBackgroundResource(R.drawable.bg_round_on)
                v.setTextColor(Color.rgb(26, 20, 4))
                v.text = if (abs(s.z - eff) < 0.05f) fmtZ(s.z) + "×" else fmtZ(eff) + "×"
            } else {
                v.setBackgroundResource(R.drawable.bg_round)
                v.setTextColor(Color.WHITE)
                v.text = fmtZ(s.z)
            }
        }
    }

    private fun fmtZ(z: Float): String {
        val r = (z * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    // ---------------------------------------------------------------- 手势：对焦、缩放

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val z = (zs.zoomRatio * d.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(z)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cam = camera ?: return false
                cam.cameraControl.cancelFocusAndMetering()
                exposureRow.visibility = View.GONE
                return true
            }
        })
        overlay.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private val hideExposure = Runnable { exposureRow.visibility = View.GONE }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        overlay.showFocus(x, y)
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(6, TimeUnit.SECONDS)
            .build()
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            val ok = try { f.get().isFocusSuccessful } catch (e: Exception) { false }
            overlay.focusResult(ok)
        }, ContextCompat.getMainExecutor(this))
        if (cam.cameraInfo.exposureState.isExposureCompensationSupported && mode != Mode.PRO) {
            exposureRow.visibility = View.VISIBLE
            main.removeCallbacks(hideExposure)
            main.postDelayed(hideExposure, 5000)
        }
    }

    private fun setupExposure() {
        val cam = camera ?: return
        val es = cam.cameraInfo.exposureState
        if (!es.isExposureCompensationSupported) { exposureRow.visibility = View.GONE; return }
        val range = es.exposureCompensationRange
        val step = es.exposureCompensationStep.toFloat()
        exposureSeek.max = range.upper - range.lower
        exposureSeek.progress = es.exposureCompensationIndex - range.lower
        fun label(idx: Int) { exposureVal.text = String.format(Locale.US, "%+.1f", idx * step) }
        label(es.exposureCompensationIndex)
        exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val idx = p + range.lower
                camera?.cameraControl?.setExposureCompensationIndex(idx)
                label(idx)
                main.removeCallbacks(hideExposure)
                main.postDelayed(hideExposure, 5000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // ---------------------------------------------------------------- 专业模式

    private val ISO_STEPS = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800)
    private val SHUTTER_STEPS = doubleArrayOf(1.0 / 8000, 1.0 / 4000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0)
    private val WB_NAMES = arrayOf("自动", "白炽灯", "荧光灯", "日光", "阴天")
    private val WB_MODES = intArrayOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    )

    private fun fmtShutter(s: Double) = if (s >= 1) "${s.roundToInt()}s" else "1/${(1 / s).roundToInt()}"

    private val proTicker = object : Runnable {
        override fun run() {
            if (mode != Mode.PRO) return
            val iso = liveIso; val ex = liveExposure; val fd = liveFocus
            proInfo.text = buildString {
                append("ISO ${iso ?: "-"}")
                append("  ·  快门 ${ex?.let { fmtShutter(it / 1e9) } ?: "-"}")
                append("  ·  对焦 ${fd?.let { if (it <= 0.01f) "∞" else String.format(Locale.US, "%.2fm", 1f / it) } ?: "-"}")
            }
            main.postDelayed(this, 400)
        }
    }

    private fun buildProPanel() {
        proPanel.removeAllViews()
        main.removeCallbacks(proTicker)
        if (mode != Mode.PRO) { proPanel.visibility = View.GONE; proInfo.visibility = View.GONE; return }
        proPanel.visibility = View.VISIBLE
        proInfo.visibility = View.VISIBLE
        main.post(proTicker)
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        if (manualSensor && isoRange != null && expRange != null) {
            val isos = ISO_STEPS.filter { it >= isoRange.lower && it <= isoRange.upper }
            addProRow("ISO", isos.size, isos.indexOf(proIso ?: -1) + 1, { i -> if (i == 0) "自动" else isos[i - 1].toString() }) { i ->
                proIso = if (i == 0) null else isos[i - 1]; applyPro()
            }
            val shs = SHUTTER_STEPS.filter { val ns = (it * 1e9).toLong(); ns >= expRange.lower && ns <= expRange.upper }
            val curSh = proExposure?.let { e -> shs.indexOfFirst { abs(it * 1e9 - e) < 1 } } ?: -1
            addProRow("快门", shs.size, curSh + 1, { i -> if (i == 0) "自动" else fmtShutter(shs[i - 1]) }) { i ->
                proExposure = if (i == 0) null else (shs[i - 1] * 1e9).toLong(); applyPro()
            }
        } else {
            proPanel.addView(TextView(this).apply {
                text = "这支手机没有开放手动 ISO 和快门给第三方 App"
                setTextColor(Color.rgb(255, 150, 140)); textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        addProRow("白平衡", WB_NAMES.size - 1, proWb, { i -> WB_NAMES[i] }) { i -> proWb = i; applyPro() }
        if (minFocus > 0f) {
            val steps = 20
            val cur = proFocus?.let { (it / minFocus * (steps - 1)).roundToInt() + 1 } ?: 0
            addProRow("对焦", steps, cur, { i ->
                if (i == 0) "自动" else {
                    val dio = minFocus * (i - 1) / (steps - 1)
                    if (dio <= 0.01f) "∞" else String.format(Locale.US, "%.2fm", 1f / dio)
                }
            }) { i -> proFocus = if (i == 0) null else minFocus * (i - 1) / (steps - 1); applyPro() }
        }
    }

    private fun addProRow(name: String, max: Int, cur: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val n = TextView(this).apply { text = name; setTextColor(Color.rgb(255, 182, 39)); textSize = 13f }
        val v = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.END; text = label(cur) }
        val sb = SeekBar(this).apply {
            this.max = max; progress = cur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { v.text = label(p); onChange(p) } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(n, LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(sb, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(v, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        proPanel.addView(row)
    }

    private fun applyPro() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (mode == Mode.PRO) {
            if (proIso != null || proExposure != null) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, proIso ?: liveIso ?: 100)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, proExposure ?: liveExposure ?: 16_666_666L)
            }
            if (proWb != 0) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WB_MODES[proWb])
            proFocus?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
        }
        try { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- 快门

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        shutter.setOnClickListener { onShutter() }
        shutter.setOnLongClickListener {
            if ((mode == Mode.PHOTO || mode == Mode.PRO) && timerSec == 0 && !counting) {
                bursting = true; burstCount = 0
                takePhoto(burst = true)
                true
            } else false
        }
        shutter.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                if (bursting) {
                    bursting = false
                    if (burstCount > 0) toast("连拍 $burstCount 张")
                }
            }
            false
        }
    }

    private fun updateShutterIcon() {
        shutter.setBackgroundResource(
            when {
                mode == Mode.VIDEO && recording != null -> R.drawable.shutter_stop
                mode == Mode.VIDEO -> R.drawable.shutter_video
                else -> R.drawable.shutter_photo
            }
        )
    }

    private fun onShutter() {
        if (counting) { cancelCountdown(); return }
        if (mode == Mode.VIDEO) {
            if (recording != null) { recording?.stop(); return }
            withTimer { startRecording() }
        } else {
            withTimer { takePhoto(false) }
        }
    }

    private var countdownLeft = 0
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!counting) return
            if (countdownLeft <= 0) {
                counting = false
                countdown.visibility = View.GONE
                countdownAction?.invoke()
                return
            }
            countdown.text = countdownLeft.toString()
            countdownLeft--
            main.postDelayed(this, 1000)
        }
    }
    private var countdownAction: (() -> Unit)? = null

    private fun withTimer(action: () -> Unit) {
        if (timerSec == 0) { action(); return }
        counting = true
        countdownLeft = timerSec
        countdownAction = action
        countdown.visibility = View.VISIBLE
        main.post(countdownTick)
    }

    private fun cancelCountdown() {
        counting = false
        main.removeCallbacks(countdownTick)
        countdown.visibility = View.GONE
        toast("已取消定时")
    }

    private fun newValues(mime: String): ContentValues {
        val name = "AC_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, prefs.folder)
        }
    }

    private fun blink() {
        flashView.alpha = 0.8f
        flashView.animate().alpha(0f).setDuration(180).start()
    }

    private fun takePhoto(burst: Boolean) {
        val ic = imageCapture ?: return
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newValues("image/jpeg")
        ).build()
        blink()
        if (prefs.shutterSound) try { sound.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) { }
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                r.savedUri?.let { setThumb(it, false) }
                if (burst) {
                    burstCount++
                    if (bursting && burstCount < 30) takePhoto(true) else bursting = false
                }
            }
            override fun onError(e: ImageCaptureException) {
                bursting = false
                toast("拍照失败：${e.message}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(newValues("video/mp4"))
            .build()
        var pending = vc.output.prepareRecording(this, out)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    recTime.visibility = View.VISIBLE
                    recTime.text = "● 00:00"
                    updateShutterIcon()
                    setChromeEnabled(false)
                }
                is VideoRecordEvent.Status -> {
                    val s = TimeUnit.NANOSECONDS.toSeconds(ev.recordingStats.recordedDurationNanos)
                    recTime.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recTime.visibility = View.GONE
                    updateShutterIcon()
                    setChromeEnabled(true)
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) toast("录像出错（代码 ${ev.error}）")
                    else setThumb(ev.outputResults.outputUri, true)
                }
            }
        }
    }

    private fun setChromeEnabled(on: Boolean) {
        val a = if (on) 1f else 0.3f
        modeBar.alpha = a; btnSwitch.alpha = a; btnRatio.alpha = a; thumb.alpha = a
    }

    // ---------------------------------------------------------------- 缩略图

    private fun setThumb(uri: Uri, video: Boolean) {
        lastUri = uri; lastIsVideo = video
        Thread {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(240, 240), null)
                else if (!video) contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 })
                } else null
            } catch (e: Exception) { null }
            main.post { if (bmp != null) thumb.setImageBitmap(bmp) }
        }.start()
    }

    private fun openLast() {
        val u = lastUri ?: run { toast("还没有拍照"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, if (lastIsVideo) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { toast("找不到可以打开照片的 App") }
    }

    // ---------------------------------------------------------------- 设置与镜头信息

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun header(t: String) = box.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.rgb(255, 182, 39)); setPadding(0, dp(14), 0, dp(4))
        })
        fun sw(label: String, value: Boolean, onSet: (Boolean) -> Unit) = box.addView(SwitchCompat(this).apply {
            text = label; textSize = 15f; isChecked = value
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c -> onSet(c) }
        })
        var needRebind = false
        header("照片")
        sw("最高画质（拍照稍慢）", prefs.maxQuality) { prefs.maxQuality = it; needRebind = true }
        sw("最高分辨率", prefs.maxResolution) { prefs.maxResolution = it; needRebind = true }
        header("录像")
        sw("4K 分辨率（关闭为 1080p）", prefs.video4k) { prefs.video4k = it; needRebind = true }
        sw("60 fps（关闭为 30 fps）", prefs.fps60) { prefs.fps60 = it; fps60Fallback = false; needRebind = true }
        header("操作")
        sw("音量键拍照", prefs.volumeShutter) { prefs.volumeShutter = it }
        sw("快门声音", prefs.shutterSound) { prefs.shutterSound = it }
        header("保存位置")
        if (Build.VERSION.SDK_INT >= 29) {
            sw("存到系统相册的「Camera」文件夹（关闭为 AutoCamera 文件夹）", prefs.folder == "DCIM/Camera") {
                prefs.folder = if (it) "DCIM/Camera" else "DCIM/AutoCamera"
            }
        } else {
            box.addView(TextView(this).apply { text = "照片保存在相册的 Pictures 文件夹"; textSize = 14f })
        }
        header("镜头")
        box.addView(TextView(this).apply {
            text = "查看这支手机的镜头信息 ›"; textSize = 15f; setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { showLensInfo() }
        })
        box.addView(TextView(this).apply {
            text = "版本 ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }}"
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(16), 0, 0)
        })
        AlertDialog.Builder(this, R.style.Dialog_AutoCamera)
            .setTitle("设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("完成", null)
            .setOnDismissListener { if (needRebind && recording == null) rebind() }
            .show()
    }

    private fun showLensInfo() {
        val r = report
        val msg = if (r == null) "无法读取镜头信息。" else buildString {
            appendLine(r.summary)
            appendLine()
            for (s in r.stops) {
                val t = when (s.type) { "uw" -> "超广角"; "main" -> "主镜头"; "crop" -> "主镜头裁切"; else -> "长焦" }
                append("${fmtZ(s.z)}×  ${s.mm}mm  $t")
                s.aperture?.let { append("  f/" + String.format(Locale.US, "%.2f", it)) }
                if (s.mp > 0) append("  ${s.mp}MP")
                append("  [${s.source}]")
                if (!s.available) append("  未开放")
                appendLine()
            }
            appendLine()
            appendLine("系统原始数据：")
            r.raw.forEach { appendLine(it) }
        }
        val tv = TextView(this).apply {
            text = msg; textSize = 13f; setTextIsSelectable(true); setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, R.style.Dialog_AutoCamera)
            .setTitle("镜头信息")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("关闭", null)
            .show()
    }
}
