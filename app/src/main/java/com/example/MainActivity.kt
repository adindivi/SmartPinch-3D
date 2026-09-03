package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONArray
import java.io.File

class MainActivity : ComponentActivity() {

    private var currentWebView: WebView? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    // 파일 선택 런처 (GLB/GLTF 업로드 지원)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            fileChooserCallback?.onReceiveValue(arrayOf(uri))
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    // 카메라 권한 요청 런처
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "카메라 권한이 승인되었습니다.", Toast.LENGTH_SHORT).show()
            pendingPermissionRequest?.let { request ->
                request.grant(request.resources)
                pendingPermissionRequest = null
            }
            currentWebView?.reload()
        } else {
            Toast.makeText(this, "제스처 인식을 위해 카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 초기 카메라 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MyApplicationTheme {
                CADViewerScreen(
                    onWebViewCreated = { webView ->
                        currentWebView = webView
                    },
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onPermissionRequest = { request ->
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.resources)
                        } else {
                            pendingPermissionRequest = request
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onShowFileChooser = { callback ->
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = callback
                        filePickerLauncher.launch("*/*")
                    }
                )
            }
        }
    }
}

/**
 * 3D CAD Viewer 메인 화면 Composable
 */
@Composable
fun CADViewerScreen(
    onWebViewCreated: (WebView) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onShowFileChooser: (android.webkit.ValueCallback<Array<android.net.Uri>>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // WebView 인스턴스 구성
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0B0F19"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // 하드웨어 가속 강제 활성화 (부드러운 60fps WebGL)
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            // JavaScript Interface 바인딩
            addJavascriptInterface(
                WebAppInterface(context, onRequestCameraPermission),
                "AndroidApp"
            )

            // WebChromeClient 설정 (카메라 권한 승인, 파일 선택, 콘솔 로깅)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    for (resource in request.resources) {
                        if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                            onPermissionRequest(request)
                            return
                        }
                    }
                    request.grant(request.resources)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback != null) {
                        onShowFileChooser(filePathCallback)
                        return true
                    }
                    return false
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d("CAD_WebView_Console", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                    return true
                }
            }

            // WebViewClient 설정
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("CAD_WebView", "Page loaded: $url")
                }
            }

            // 로컬 Asset index.html 로드
            loadUrl("file:///android_asset/index.html")
            onWebViewCreated(this)
        }
    }

    // 액티비티 생명주기 이벤트 감지 및 JavaScript 브릿지 호출
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.evaluateJavascript("if (typeof onAppPause === 'function') onAppPause();", null)
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.evaluateJavascript("if (typeof onAppResume === 'function') onAppResume();", null)
                }
                Lifecycle.Event.ON_DESTROY -> {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            (webView.parent as? ViewGroup)?.removeView(webView)
            try {
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * JavaScript Interface 브릿지 클래스 (AndroidApp)
 */
class WebAppInterface(
    private val context: Context,
    private val onRequestCameraPermission: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * 단일 진동 실행 (밀리초)
     */
    @JavascriptInterface
    fun vibrate(duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        duration.coerceIn(10L, 1000L),
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration.coerceIn(10L, 1000L))
            }
        } catch (e: Exception) {
            Log.w("WebAppInterface", "Vibration error: ${e.message}")
        }
    }

    /**
     * 패턴 진동 실행 (JSON Array 문자열: e.g. "[100, 50, 100]")
     */
    @JavascriptInterface
    fun vibrate(patternJson: String) {
        try {
            val jsonArray = JSONArray(patternJson)
            val timings = LongArray(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                timings[i] = jsonArray.getLong(i)
            }

            if (timings.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(timings, -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(timings, -1)
                }
            }
        } catch (e: Exception) {
            Log.w("WebAppInterface", "Pattern vibration parse error: ${e.message}")
        }
    }

    /**
     * 안드로이드 네이티브 토스트 팝업 표시
     */
    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 런타임 카메라 권한 요청 트리거
     */
    @JavascriptInterface
    fun requestCameraPermission() {
        mainHandler.post {
            onRequestCameraPermission()
        }
    }

    /**
     * Galaxy S24 로컬 저장소 내 3D 모델 (.glb, .gltf) 목록 JSON 반환
     */
    @JavascriptInterface
    fun getLocalModelList(): String {
        return try {
            val modelFiles = mutableListOf<String>()
            val modelsDir = File(context.filesDir, "models")
            if (modelsDir.exists() && modelsDir.isDirectory) {
                modelsDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".glb", ignoreCase = true) || file.name.endsWith(".gltf", ignoreCase = true)) {
                        modelFiles.add(file.name)
                    }
                }
            }
            JSONArray(modelFiles).toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    /**
     * 로컬 3D 모델 바이너리 파일을 Base64 문자열로 인코딩하여 반환
     */
    @JavascriptInterface
    fun getLocalModelData(fileName: String): String {
        return try {
            val targetFile = File(File(context.filesDir, "models"), fileName)
            if (targetFile.exists() && targetFile.isFile) {
                val bytes = targetFile.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Failed to read local model: ${e.message}")
            ""
        }
    }

    /**
     * 3D 뷰포트 캡처 PNG 이미지를 저장
     */
    @JavascriptInterface
    fun saveImage(base64Data: String, fileName: String) {
        mainHandler.post {
            try {
                val cleanBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/SmartPinch3D")
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let { destUri ->
                        context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                            outputStream.write(imageBytes)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                    val appDir = File(picturesDir, "SmartPinch3D").apply { mkdirs() }
                    val file = File(appDir, fileName)
                    file.writeBytes(imageBytes)
                }
                Toast.makeText(context, "PNG 캡처 저장 완료: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("WebAppInterface", "Failed to save image: ${e.message}")
                Toast.makeText(context, "PNG 캡처 저장 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
