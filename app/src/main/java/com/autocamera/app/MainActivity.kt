package com.autocamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
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
import androidx.camera.core.DynamicRange
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

    enum class Mode(val label: String, val icon: String) {
        PHOTO("拍照", "拍"), PORTRAIT("人像", "人"), VIDEO("录像", "录"), PRO("专业", "PRO"),
        NIGHT("夜景", "夜"), HIRES("高像素", "HD"), WIDE("宽幅", "宽"), PRO_VIDEO("专业录像", "PRO")
    }

    private fun isVideo(m: Mode = mode) = m == Mode.VIDEO || m == Mode.PRO_VIDEO
    private fun isPro(m: Mode = mode) = m == Mode.PRO || m == Mode.PRO_VIDEO
    private fun isPhotoLike(m: Mode = mode) = m == Mode.PHOTO || m == Mode.PRO || m == Mode.HIRES || m == Mode.WIDE

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var frame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var flashView: View
    private lateinit var topBar: LinearLayout
    private lateinit var optionScroll: View
    private lateinit var optionStrip: LinearLayout
    private lateinit var quickPanel: LinearLayout
    private lateinit var zoomHint: TextView
    private lateinit var recTime: TextView
    private lateinit var countdown: TextView
    private lateinit var proInfo: TextView
    private lateinit var proPanel: LinearLayout
    private lateinit var exposureRow: LinearLayout
    private lateinit var exposureSeek: SeekBar
    private lateinit var exposureVal: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeBar: LinearLayout
    private lateinit var modeScroll: android.widget.HorizontalScrollView
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
    private var torchOn = false
    private var sportOn = false
    private var macroOn = false
    private var intervalSec = 0
    private var intervalRunning = false
    private var intervalShots = 0
    private var hdrExtAvailable = false
    private var zoomCycles: Map<LensStop, List<Float>> = emptyMap()

    // 这支手机实际支持的录像／拍照规格（每次绑定前从相机读取）
    private var videoQualityList: List<Quality> = listOf(Quality.FHD)
    private var fpsList: List<Int> = listOf(30)
    private var hdrSupported = false
    private var stabSupported = false
    private var photoStdMp = 12
    private var photoMaxMp = 12

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
        stopInterval()
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
        optionScroll = findViewById(R.id.optionScroll)
        optionStrip = findViewById(R.id.optionStrip)
        quickPanel = findViewById(R.id.quickPanel)
        zoomHint = findViewById(R.id.zoomHint)
        recTime = findViewById(R.id.recTime)
        countdown = findViewById(R.id.countdown)
        proInfo = findViewById(R.id.proInfo)
        proPanel = findViewById(R.id.proPanel)
        exposureRow = findViewById(R.id.exposureRow)
        exposureSeek = findViewById(R.id.exposureSeek)
        exposureVal = findViewById(R.id.exposureVal)
        zoomRow = findViewById(R.id.zoomRow)
        modeBar = findViewById(R.id.modeBar)
        modeScroll = findViewById(R.id.modeScroll)
        setupModeWheel()
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
        Mode.PHOTO -> if (prefs.autoHdr && hdrExtAvailable && !sportOn && !macroOn) ExtensionMode.HDR else null
        else -> null
    }

    private fun modeAvailable(m: Mode): Boolean {
        if (m == Mode.HIRES) return facing == CameraSelector.LENS_FACING_BACK && photoMaxMp > photoStdMp + 1
        if (m == Mode.PRO_VIDEO || m == Mode.WIDE) return true
        val ext = when (m) { Mode.PORTRAIT -> ExtensionMode.BOKEH; Mode.NIGHT -> ExtensionMode.NIGHT; else -> return true }
        val em = extensions ?: return false
        val sel = CameraSelector.Builder().requireLensFacing(facing).build()
        return try { em.isExtensionAvailable(sel, ext) } catch (e: Exception) { false }
    }

    /** 依画面比例调整取景框大小，然后重新绑定相机。 */
    private fun rebind() {
        val w = root.width
        if (w == 0) { root.post { rebind() }; return }
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        when (if (isVideo()) 1 else if (mode == Mode.HIRES) 0 else if (mode == Mode.WIDE) 9 else prefs.ratio) {
            0 -> { lp.width = w; lp.height = w * 4 / 3 }
            9 -> { lp.width = w; lp.height = w * 24 / 65 }
            1 -> { lp.width = w; lp.height = w * 16 / 9 }
            2 -> { lp.width = w; lp.height = w }
            else -> { lp.width = FrameLayout.LayoutParams.MATCH_PARENT; lp.height = FrameLayout.LayoutParams.MATCH_PARENT }
        }
        lp.gravity = Gravity.CENTER
        frame.layoutParams = lp
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
            val ok = em != null && try { em.isExtensionAvailable(CameraSelector.Builder().requireLensFacing(facing).build(), ext) } catch (e: Exception) { false }
            if (em != null && ok) {
                boundCameraId = null
                selector = em.getExtensionEnabledCameraSelector(CameraSelector.Builder().requireLensFacing(facing).build(), ext)
            } else if (mode != Mode.PHOTO) {
                toast("这支手机没有开放「${mode.label}」模式给第三方 App")
                mode = Mode.PHOTO; buildModeBar()
            }
        }

        val ratioNow = if (mode == Mode.HIRES || mode == Mode.WIDE) 0 else prefs.ratio
        val aspect = if (isVideo() || ratioNow == 1 || ratioNow == 3) androidx.camera.core.AspectRatio.RATIO_16_9 else androidx.camera.core.AspectRatio.RATIO_4_3
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

        val preInfo = try { selector.filter(p.availableCameraInfos).firstOrNull() } catch (e: Exception) { null }
        if (preInfo != null) readCapabilities(preInfo)

        val viewPort = previewView.viewPort
        val group = UseCaseGroup.Builder().addUseCase(preview)
        if (viewPort != null) group.setViewPort(viewPort)

        if (isVideo()) {
            val q = currentQuality()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            val fps = currentFps()
            vb.setTargetFrameRate(Range(fps, fps))
            if (prefs.videoHdr && hdrSupported) vb.setDynamicRange(DynamicRange.HLG_10_BIT)
            if (prefs.videoStab && stabSupported) vb.setVideoStabilizationEnabled(true)
            val vc = vb.build()
            videoCapture = vc
            group.addUseCase(vc)
        } else {
            val resBuilder = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
            if ((prefs.maxResolution || mode == Mode.HIRES) && photoMaxMp > photoStdMp) {
                resBuilder.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (ext == null) resBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            } else {
                val target = if (aspect == androidx.camera.core.AspectRatio.RATIO_16_9) Size(4000, 2250) else Size(4000, 3000)
                resBuilder.setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            }
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
            if (isVideo()) {
                when {
                    prefs.videoHdr && hdrSupported -> { prefs.videoHdr = false; toast("目前的规格不支持 HDR，已关闭 HDR"); bindUseCases(); return }
                    currentFps() > 30 -> { prefs.videoFps = 30; toast("目前的分辨率不支持这个帧率，已改为 30fps"); bindUseCases(); return }
                    currentQuality() == Quality.UHD -> { prefs.videoQuality = "FHD"; toast("无法使用 4K，已改为 1080P"); bindUseCases(); return }
                    prefs.videoStab && stabSupported -> { prefs.videoStab = false; toast("已关闭防抖"); bindUseCases(); return }
                }
            }
            if (!isVideo() && prefs.maxResolution && mode != Mode.HIRES) {
                prefs.maxResolution = false; toast("无法使用最高像素，已改为标准像素"); bindUseCases(); return
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
        if (isVideo()) cam.cameraControl.enableTorch(torchOn) else applyFlash()
        try {
            hdrExtAvailable = extensions?.isExtensionAvailable(CameraSelector.Builder().requireLensFacing(facing).build(), ExtensionMode.HDR) == true
        } catch (_: Exception) { hdrExtAvailable = false }
        buildTopBar()
        if (quickPanel.visibility == View.VISIBLE && quickPanelKind == 0) buildQuickPanel()
        buildModeBar()
        buildZoomRow()
        setupExposure()
        buildProPanel()
        applyPro()
        updateShutterIcon()
    }

    // ---------------------------------------------------------------- 顶部按钮

    private fun readCapabilities(info: androidx.camera.core.CameraInfo) {
        try {
            val caps = Recorder.getVideoCapabilities(info)
            hdrSupported = caps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)
            stabSupported = caps.isStabilizationSupported
            val dr = if (prefs.videoHdr && hdrSupported) DynamicRange.HLG_10_BIT else DynamicRange.SDR
            val sup = caps.getSupportedQualities(dr)
            videoQualityList = listOf(Quality.HD, Quality.FHD, Quality.UHD).filter { it in sup }.ifEmpty { listOf(Quality.FHD) }
        } catch (e: Exception) {
            hdrSupported = false; stabSupported = false
            videoQualityList = listOf(Quality.HD, Quality.FHD)
        }
        fpsList = try {
            info.supportedFrameRateRanges.filter { it.lower == it.upper && it.upper in 24..60 }.map { it.upper }.distinct().sorted()
        } catch (e: Exception) { emptyList() }.ifEmpty { listOf(30) }
        try {
            val c2 = Camera2CameraInfo.from(info)
            val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val sizes = (map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()) +
                    (map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList())
                val want = if (prefs.ratio == 1 || prefs.ratio == 3) 16f / 9f else 4f / 3f
                val match = sizes.filter { abs(max(it.width, it.height).toFloat() / min(it.width, it.height) - want) < 0.05f }
                val mps = match.map { (it.width.toLong() * it.height / 1_000_000L).toInt() }
                if (mps.isNotEmpty()) {
                    photoMaxMp = mps.max()
                    photoStdMp = mps.filter { it <= 13 }.maxOrNull() ?: mps.min()
                }
            }
        } catch (_: Exception) { }
    }

    private fun qualityName(q: Quality) = when (q) { Quality.UHD -> "4K"; Quality.FHD -> "1080P"; Quality.HD -> "720P"; else -> "480P" }
    private fun qualityKey(q: Quality) = when (q) { Quality.UHD -> "UHD"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; else -> "SD" }

    private fun currentQuality(): Quality {
        val want = when (prefs.videoQuality) { "UHD" -> Quality.UHD; "HD" -> Quality.HD; else -> Quality.FHD }
        return if (want in videoQualityList) want else (videoQualityList.lastOrNull { it != Quality.UHD } ?: videoQualityList.last())
    }

    private fun currentFps(): Int {
        val f = prefs.videoFps
        return if (f in fpsList) f else (fpsList.filter { it <= 30 }.maxOrNull() ?: fpsList.first())
    }

    private class TopItem(val label: String, val active: Boolean, val onClick: () -> Unit)

    /** 取景画面顶部的快捷列，跟系统相机一样：拍照、录像显示不同的选项。 */
    private fun buildTopBar() {
        hideOptions()
        topBar.removeAllViews()
        val items = mutableListOf<TopItem>()
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        if (isVideo()) {
            if (hasFlash) items.add(TopItem(if (torchOn) "补光 开" else "补光 关", torchOn) {
                torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn); buildTopBar()
            })
            val qs = videoQualityList
            val cq = currentQuality()
            items.add(TopItem(qualityName(cq), true) {
                showOptions(qs.map { qualityName(it) }, qs.indexOf(cq)) { i ->
                    prefs.videoQuality = qualityKey(qs[i]); rebind()
                }
            })
            val cf = currentFps()
            items.add(TopItem("${cf}fps", true) {
                showOptions(fpsList.map { "$it fps" }, fpsList.indexOf(cf)) { i -> prefs.videoFps = fpsList[i]; rebind() }
            })
            if (hdrSupported) items.add(TopItem("HDR", prefs.videoHdr) { prefs.videoHdr = !prefs.videoHdr; rebind() })
            if (stabSupported) items.add(TopItem("防抖", prefs.videoStab) { prefs.videoStab = !prefs.videoStab; rebind() })
        } else {
            if (hasFlash) items.add(TopItem(arrayOf("自动闪光", "闪光 开", "闪光 关", "常亮")[flash], flash != 2) {
                flash = (flash + 1) % 4; applyFlash(); buildTopBar()
            })
            if (extensionFor(mode) == ExtensionMode.HDR) items.add(TopItem("HDR", true) { prefs.autoHdr = false; rebind() })
            if (sportOn) items.add(TopItem("运动", true) { sportOn = false; applyPro(); buildTopBar() })
            if (macroOn) items.add(TopItem("微距", true) { macroOn = false; applyPro(); buildTopBar() })
            if (timerSec > 0) items.add(TopItem("${timerSec}s", true) { timerSec = 0; buildTopBar() })
            if (intervalSec > 0) items.add(TopItem("间隔${intervalSec}s", true) { intervalSec = 0; stopInterval(); buildTopBar() })
            val actual = imageCapture?.resolutionInfo?.resolution?.let { (it.width.toLong() * it.height / 1_000_000L).toInt() }
            if (photoMaxMp > photoStdMp + 1 && mode != Mode.HIRES && mode != Mode.WIDE && extensionFor(mode) == null) {
                val label = "${actual ?: if (prefs.maxResolution) photoMaxMp else photoStdMp}M"
                items.add(TopItem(label, prefs.maxResolution) {
                    showOptions(listOf("标准 ${photoStdMp}M", "最高 ${photoMaxMp}M"), if (prefs.maxResolution) 1 else 0) { i ->
                        prefs.maxResolution = i == 1; rebind()
                    }
                })
            }
        }
        items.add(TopItem("⋮⋮", quickPanel.visibility == View.VISIBLE && quickPanelKind == 0) { toggleQuickPanel() })
        for (it in items) {
            topBar.addView(TextView(this).apply {
                text = it.label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (it.active) Color.rgb(255, 182, 39) else Color.WHITE)
                setOnClickListener { _ -> if (recording == null || it.label.startsWith("补光")) it.onClick() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** 点顶部按钮后展开的一排选项，例如 720P / 1080P / 4K。 */
    private fun showOptions(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (optionScroll.visibility == View.VISIBLE) { hideOptions(); return }
        optionStrip.removeAllViews()
        options.forEachIndexed { i, label ->
            optionStrip.addView(TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(if (i == selected) Color.rgb(255, 182, 39) else Color.WHITE)
                paint.isFakeBoldText = i == selected
                setOnClickListener { hideOptions(); if (i != selected) onPick(i) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        optionScroll.visibility = View.VISIBLE
    }

    private fun hideOptions() { optionScroll.visibility = View.GONE }

    private fun applyFlash() {
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

    private fun pinnedModes(): List<Mode> =
        prefs.pinnedModes.split(",").mapNotNull { n -> Mode.values().firstOrNull { it.name == n } }

    private fun wheelModes(): List<Mode> {
        val pinned = pinnedModes().filter { modeAvailable(it) }
        return if (mode in pinned) pinned else pinned + mode
    }

    /** 模式转盘：当前模式固定在快门正上方的中间；滑到哪个就直接切换，不用再点一下。 */
    private fun buildModeBar() {
        modeBar.removeAllViews()
        val side = max(0, (if (root.width > 0) root.width else resources.displayMetrics.widthPixels) / 2 - dp(40))
        modeBar.setPadding(side, 0, side, 0)
        val moreOpen = quickPanel.visibility == View.VISIBLE && quickPanelKind == 1
        fun add(label: String, on: Boolean, tagValue: Any, click: () -> Unit) {
            val t = TextView(this).apply {
                text = label
                textSize = if (on) 16f else 15f
                setPadding(dp(14), 0, dp(14), 0)
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.rgb(255, 182, 39) else Color.argb(200, 255, 255, 255))
                paint.isFakeBoldText = on
                tag = tagValue
                setOnClickListener { click() }
            }
            modeBar.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        for (m in wheelModes()) add(m.label, m == mode && !moreOpen, m) { selectFromWheel(m) }
        add("更多", moreOpen, "more") { if (!moreOpen) toggleMorePanel() else hideQuickPanel() }
        modeBar.post { centerWheelOn(if (moreOpen) "more" else mode, smooth = false) }
    }

    private fun wheelChild(tagValue: Any): View? {
        for (i in 0 until modeBar.childCount) if (modeBar.getChildAt(i).tag == tagValue) return modeBar.getChildAt(i)
        return null
    }

    private var wheelProgrammatic = false

    private fun centerWheelOn(tagValue: Any, smooth: Boolean) {
        val v = wheelChild(tagValue) ?: return
        val x = v.left + v.width / 2 - modeScroll.width / 2
        wheelProgrammatic = true
        if (smooth) modeScroll.smoothScrollTo(x, 0) else modeScroll.scrollTo(x, 0)
        main.postDelayed({ wheelProgrammatic = false }, if (smooth) 400 else 50)
    }

    private fun selectFromWheel(m: Mode) {
        if (quickPanel.visibility == View.VISIBLE) quickPanel.visibility = View.GONE
        if (m == mode) { buildModeBar(); buildTopBar(); return }
        if (recording != null || counting || intervalRunning) { centerWheelOn(mode, true); return }
        setMode(m)
    }

    /** 手指放开、滚动停下后，把最靠近中间的模式选起来。 */
    private val wheelSettle = Runnable {
        val center = modeScroll.scrollX + modeScroll.width / 2
        var best: View? = null
        var bestD = Int.MAX_VALUE
        for (i in 0 until modeBar.childCount) {
            val c = modeBar.getChildAt(i)
            val d = abs(c.left + c.width / 2 - center)
            if (d < bestD) { bestD = d; best = c }
        }
        val t = best?.tag ?: return@Runnable
        if (t == "more") {
            if (!(quickPanel.visibility == View.VISIBLE && quickPanelKind == 1)) toggleMorePanel()
            else centerWheelOn("more", true)
        } else if (t is Mode) {
            selectFromWheel(t)
            if (t == mode) centerWheelOn(t, true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupModeWheel() {
        modeScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (wheelProgrammatic) return@setOnScrollChangeListener
            main.removeCallbacks(wheelSettle)
            main.postDelayed(wheelSettle, 160)
        }
        modeScroll.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) wheelProgrammatic = false
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                main.removeCallbacks(wheelSettle)
                main.postDelayed(wheelSettle, 160)
            }
            false
        }
    }

    /** 在取景画面上左右滑动切换模式。 */
    private fun stepMode(dir: Int) {
        val list = wheelModes()
        val i = list.indexOf(mode)
        val j = i + dir
        if (i < 0 || j < 0 || j >= list.size) return
        hideQuickPanel()
        selectFromWheel(list[j])
    }

    private fun setMode(m: Mode) {
        if (m == mode || recording != null || counting || intervalRunning) return
        mode = m
        sportOn = false; macroOn = false
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

    /** 摄影常用焦段：点同一颗镜头时依序放大，例如主摄 24→28→35mm（1→1.2→1.5×）。 */
    private val CLASSIC_MM = intArrayOf(14, 16, 20, 24, 28, 35, 50, 70, 85, 100, 135, 200, 300)

    /**
     * 点同一颗镜头时的放大顺序（参考小米、OPPO 系统相机）：
     * 主摄 1 → 1.2 → 1.5×；光学长焦 ×1.25（2.5× → 3.1×，60 → 75mm）；最后一颗裁切镜头 ×2（5× → 10×）。
     */
    private fun computeCycles(stops: List<LensStop>): Map<LensStop, List<Float>> {
        val avail = stops.filter { it.available }.sortedBy { it.z }
        val out = HashMap<LensStop, List<Float>>()
        for ((i, st) in avail.withIndex()) {
            val next = avail.getOrNull(i + 1)?.z
            val maxZ = if (st.cameraId == null) defaultZoomMax else 99f
            val cands = when (st.type) {
                "main" -> listOf(st.z * 1.2f, st.z * 1.5f)
                "tele" -> listOf(st.z * 1.25f)
                "crop" -> if (next == null) listOf(st.z * 2f) else emptyList()
                else -> emptyList()
            }.map { (it * 10).roundToInt() / 10f }
                .filter { it > st.z + 0.05f && (next == null || it < next * 0.97f) && it <= maxZ + 0.01f }
            out[st] = listOf(st.z) + cands
        }
        return out
    }

    /** 独立相机 ID 的基准倍数（同一颗镜头的裁切按钮共用）。 */
    private fun camBaseZ(id: String): Float =
        report?.stops?.firstOrNull { it.cameraId == id && it.type != "crop" }?.z
            ?: report?.stops?.firstOrNull { it.cameraId == id }?.z ?: 1f

    private val hideZoomHint = Runnable { zoomHint.visibility = View.INVISIBLE }

    private fun showZoomHint(z: Float) {
        val mainMm = report?.stops?.firstOrNull { it.type == "main" }?.mm?.takeIf { it > 0 } ?: 24
        zoomHint.text = "${(z * mainMm).roundToInt()}mm"
        zoomHint.visibility = View.VISIBLE
        main.removeCallbacks(hideZoomHint)
        main.postDelayed(hideZoomHint, 1500)
    }

    private fun buildZoomRow() {
        zoomRow.removeAllViews()
        zoomCycles = computeCycles(zoomStops())
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
            pendingZoom = if (s.cameraId == null) s.z else s.z / camBaseZ(s.cameraId)
            showZoomHint(s.z)
            bindUseCases()
            return
        }
        val eff = currentEffectiveZoom(cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f)
        val cycle = zoomCycles[s] ?: listOf(s.z)
        val idx = cycle.indexOfFirst { abs(it - eff) < 0.04f }
        val target = if (idx < 0) cycle[0] else cycle[(idx + 1) % cycle.size]
        cam.cameraControl.setZoomRatio(if (s.cameraId == null) target else target / camBaseZ(s.cameraId))
        showZoomHint(target)
    }

    private fun currentEffectiveZoom(ratio: Float): Float {
        val id = boundCameraId ?: return ratio
        return camBaseZ(id) * ratio
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
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (optionScroll.visibility == View.VISIBLE) { hideOptions(); return true }
                if (quickPanel.visibility == View.VISIBLE) { hideQuickPanel(); return true }
                focusAt(e.x, e.y); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 900f) { stepMode(if (vx < 0) 1 else -1); return true }
                return false
            }
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
        if (cam.cameraInfo.exposureState.isExposureCompensationSupported && !isPro()) {
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
            if (!isPro()) return
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
        if (!isPro()) { proPanel.visibility = View.GONE; proInfo.visibility = View.GONE; return }
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
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        if (!isPro() && extensionFor(mode) == null) {
            if (sportOn) {
                val scenes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
                if (scenes.contains(CaptureRequest.CONTROL_SCENE_MODE_ACTION)) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_ACTION)
                } else {
                    val fr = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.filter { it.lower == it.upper }?.maxByOrNull { it.upper }
                    if (fr != null) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fr)
                }
            }
            if (macroOn) {
                val afs = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
                if (afs.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)) b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            }
        }
        if (isPro()) {
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

    // ---------------------------------------------------------------- 快捷面板（⋮⋮）与「更多」模式

    private var quickPanelKind = 0  // 0 快捷设定 1 更多模式

    private class QItem(val icon: String, val label: String, val active: Boolean, val onClick: () -> Unit)

    private fun toggleQuickPanel() {
        if (quickPanel.visibility == View.VISIBLE && quickPanelKind == 0) { hideQuickPanel(); return }
        quickPanelKind = 0; buildQuickPanel(); quickPanel.visibility = View.VISIBLE; buildTopBar(); buildModeBar()
    }

    private fun toggleMorePanel() {
        if (quickPanel.visibility == View.VISIBLE && quickPanelKind == 1) { hideQuickPanel(); return }
        quickPanelKind = 1; buildMorePanel(); quickPanel.visibility = View.VISIBLE; buildTopBar(); buildModeBar()
    }

    private fun hideQuickPanel() {
        if (quickPanel.visibility != View.VISIBLE) return
        quickPanel.visibility = View.GONE
        buildTopBar(); buildModeBar()
    }

    private fun fillGrid(items: List<QItem>, header: Pair<String, () -> Unit>? = null) {
        quickPanel.removeAllViews()
        if (header != null) {
            quickPanel.addView(TextView(this).apply {
                text = header.first; textSize = 13f; gravity = Gravity.END
                setTextColor(Color.rgb(255, 182, 39)); setPadding(0, 0, dp(8), dp(6))
                setOnClickListener { header.second() }
            })
        }
        var row: LinearLayout? = null
        items.forEachIndexed { i, it ->
            if (i % 4 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                quickPanel.addView(row)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { _ -> it.onClick() }
            }
            cell.addView(TextView(this).apply {
                text = it.icon; textSize = if (it.icon.length > 2) 12f else 15f; gravity = Gravity.CENTER
                setBackgroundResource(if (it.active) R.drawable.bg_round_on else R.drawable.bg_round_light)
                setTextColor(if (it.active) Color.rgb(26, 20, 4) else Color.WHITE)
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            cell.addView(TextView(this).apply {
                text = it.label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, 0)
            })
            row!!.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rem = items.size % 4
        if (rem != 0) repeat(4 - rem) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun buildQuickPanel() {
        val items = mutableListOf<QItem>()
        if (isVideo()) {
            if (hdrSupported) items.add(QItem("HDR", "HDR 视频", prefs.videoHdr) { prefs.videoHdr = !prefs.videoHdr; rebind() })
            if (stabSupported) items.add(QItem("防抖", "防抖", prefs.videoStab) { prefs.videoStab = !prefs.videoStab; rebind() })
        } else {
            if (mode == Mode.PHOTO || mode == Mode.PRO) {
                val ratios = listOf("4:3", "16:9", "1:1", "全屏")
                items.add(QItem(ratios[prefs.ratio], "画面比例", false) { prefs.ratio = (prefs.ratio + 1) % 4; rebind() })
            }
            if (mode == Mode.PHOTO) {
                items.add(QItem("运动", "运动模式", sportOn) {
                    sportOn = !sportOn; if (sportOn) macroOn = false
                    if (extensionFor(Mode.PHOTO) == null && prefs.autoHdr && hdrExtAvailable) rebind() else { applyPro(); buildTopBar(); buildQuickPanel() }
                    if (sportOn) toast("运动模式：优先使用较快的快门，适合拍动态主体")
                })
            }
            if (mode == Mode.PHOTO || mode == Mode.PRO) {
                items.add(QItem("微距", "微距特写", macroOn) { toggleMacro() })
            }
            if (mode == Mode.PHOTO && hdrExtAvailable) {
                items.add(QItem("HDR", "自动 HDR", prefs.autoHdr) { prefs.autoHdr = !prefs.autoHdr; rebind() })
            }
            items.add(QItem(if (timerSec == 0) "关" else "${timerSec}s", "倒数计时", timerSec > 0) {
                timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }; buildQuickPanel(); buildTopBar()
            })
            if (isPhotoLike()) {
                items.add(QItem(if (intervalSec == 0) "关" else "${intervalSec}s", "间隔连拍", intervalSec > 0) {
                    intervalSec = when (intervalSec) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }; buildQuickPanel(); buildTopBar()
                    if (intervalSec > 0) toast("间隔连拍：按快门开始，每 ${intervalSec} 秒拍一张，再按一次停止")
                })
            }
        }
        items.add(QItem("#", "网格", prefs.grid) { prefs.grid = !prefs.grid; overlay.grid = prefs.grid; buildQuickPanel() })
        items.add(QItem("⚙", "设置", false) { hideQuickPanel(); showSettings() })
        fillGrid(items)
    }

    private fun buildMorePanel() {
        val pinned = pinnedModes()
        val items = Mode.values().filter { modeAvailable(it) && it !in pinned }.map { m ->
            QItem(m.icon, m.label, m == mode) { hideQuickPanel(); setMode(m) }
        }
        fillGrid(items, "编辑 ✎" to { editPinnedModes() })
        if (items.isEmpty()) quickPanel.addView(TextView(this).apply {
            text = "所有模式都已放在下方主列"; setTextColor(Color.GRAY); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        })
    }

    private fun editPinnedModes() {
        val all = Mode.values().filter { modeAvailable(it) }
        val pinned = pinnedModes().toMutableSet()
        val checked = BooleanArray(all.size) { all[it] in pinned }
        AlertDialog.Builder(this, R.style.Dialog_AutoCamera)
            .setTitle("选择放在主列的模式")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("完成") { _, _ ->
                val sel = all.filterIndexed { i, _ -> checked[i] }.ifEmpty { listOf(Mode.PHOTO) }
                prefs.pinnedModes = sel.joinToString(",") { it.name }
                buildModeBar(); if (quickPanel.visibility == View.VISIBLE) buildMorePanel()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleMacro() {
        macroOn = !macroOn
        if (macroOn) {
            sportOn = false
            val r = report
            val tele = r?.stops?.firstOrNull { it.type == "tele" && it.available }
            val uw = r?.stops?.firstOrNull { it.type == "uw" && it.available }
            val pick = when { r?.macro == "tele" && tele != null -> tele; uw != null && r?.macro != null -> uw; else -> null }
            if (pick != null && facing == CameraSelector.LENS_FACING_BACK) {
                onZoomStop(pick)
                toast(if (pick.type == "tele") "微距：已切到 ${fmtZ(pick.z)}× 长焦，离主体约 10–30 cm" else "微距：已切到超广角，靠近到 3–5 cm")
            } else {
                toast("微距：慢慢靠近主体，直到画面清晰")
            }
        }
        if (prefs.autoHdr && hdrExtAvailable && mode == Mode.PHOTO) rebind() else { applyPro(); buildTopBar(); buildQuickPanel() }
    }

    private val intervalTick = object : Runnable {
        override fun run() {
            if (!intervalRunning) return
            takePhoto(false)
            intervalShots++
            recTime.text = "间隔连拍 · 已拍 $intervalShots 张"
            main.postDelayed(this, intervalSec * 1000L)
        }
    }

    private fun startInterval() {
        intervalRunning = true
        intervalShots = 0
        recTime.visibility = View.VISIBLE
        recTime.text = "间隔连拍 · 准备中"
        updateShutterIcon()
        setChromeEnabled(false)
        main.post(intervalTick)
    }

    private fun stopInterval() {
        if (!intervalRunning) return
        intervalRunning = false
        main.removeCallbacks(intervalTick)
        recTime.visibility = View.GONE
        updateShutterIcon()
        setChromeEnabled(true)
        toast("间隔连拍结束，共 $intervalShots 张")
    }

    // ---------------------------------------------------------------- 快门

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        shutter.setOnClickListener { onShutter() }
        shutter.setOnLongClickListener {
            if (isPhotoLike() && timerSec == 0 && intervalSec == 0 && !counting) {
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
                isVideo() && recording != null -> R.drawable.shutter_stop
                isVideo() -> R.drawable.shutter_video
                intervalRunning -> R.drawable.shutter_stop
                else -> R.drawable.shutter_photo
            }
        )
    }

    private fun onShutter() {
        if (counting) { cancelCountdown(); return }
        if (intervalRunning) { stopInterval(); return }
        if (isVideo()) {
            if (recording != null) { recording?.stop(); return }
            withTimer { startRecording() }
        } else if (intervalSec > 0) {
            withTimer { startInterval() }
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
        modeBar.alpha = a; btnSwitch.alpha = a; thumb.alpha = a
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
        box.addView(TextView(this).apply {
            text = "像素、录像分辨率、帧率、HDR、防抖都在取景画面顶部，点一下就能选。"
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(4), 0, 0)
        })
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
                val t = when (s.type) { "uw" -> "超广角"; "main" -> "主镜头"; "crop" -> if (s.source == "长焦裁切") "长焦无损裁切" else "主镜头裁切"; else -> "长焦" }
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
