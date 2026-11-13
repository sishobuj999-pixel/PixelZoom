package com.sed.pixelzoom

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var viewer: PixelCanvas
    private lateinit var info: TextView
    private lateinit var data: TextView
    private lateinit var status: TextView
    private var imageBytes: ByteArray? = null
    private var imageName = "No image selected"
    private var bitmap: Bitmap? = null
    private var mode = "IMAGE"
    private var byteOffset = 0
    private val pageSize = 4096
    private lateinit var pageLabel: TextView
    private lateinit var previousPage: Button
    private lateinit var nextPage: Button
    private val repoApi = "https://api.github.com/repos/sishobuj999-pixel/PixelZoom/releases/latest"
    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        checkUpdate(silent = true)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.rgb(9, 11, 18))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "PIXELZOOM"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        val update = Button(this).apply { text = "Check update"; setOnClickListener { checkUpdate(false) } }
        header.addView(title, LinearParams(0, -2, 1f)); header.addView(update)
        root.addView(header)
        status = label("Open an image to inspect every pixel")
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Open image") { pickImage.launch("image/*") }, LinearParams(0, -2, 1f))
        actions.addView(action("Reset zoom") { viewer.resetZoom() }, LinearParams(0, -2, 1f))
        root.addView(actions)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("IMAGE", "BINARY", "HEX", "ASCII").forEach { tab ->
            tabs.addView(action(tab) { setMode(tab) }, LinearParams(0, -2, 1f))
        }
        root.addView(tabs)
        viewer = PixelCanvas(this) { x, y, color -> showPixel(x, y, color) }
        root.addView(viewer, LinearParams(-1, 0, 1f))
        info = label("Pixel information will appear here")
        root.addView(info)
        data = TextView(this).apply {
            setTextColor(Color.rgb(190, 202, 224)); textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(17, 21, 32))
            text = "Binary / HEX / ASCII data preview"
            isVerticalScrollBarEnabled = true
        }
        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previousPage = action("Previous") { changePage(-1) }
        pageLabel = label("Page 0 / 0")
        nextPage = action("Next") { changePage(1) }
        pageControls.addView(previousPage)
        pageControls.addView(pageLabel, LinearParams(0, -2, 1f))
        pageControls.addView(nextPage)
        root.addView(pageControls)
        val scroll = ScrollView(this).apply { addView(data) }
        root.addView(scroll, LinearParams(-1, dp(150)))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copy.addView(action("Copy visible page") { copyText(data.text.toString()) }, LinearParams(0, -2, 1f))
        copy.addView(action("Copy page bytes") { copyPageBytes() }, LinearParams(0, -2, 1f))
        root.addView(copy)
        setContentView(root)
    }

    private fun setMode(next: String) {
        mode = next
        byteOffset = 0
        val bytes = imageBytes
        if (bytes == null) { status.text = "Select an image first"; return }
        viewer.visibility = if (next == "IMAGE") View.VISIBLE else View.GONE
        info.visibility = if (next == "IMAGE") View.VISIBLE else View.GONE
        data.visibility = if (next == "IMAGE") View.GONE else View.VISIBLE
        pageLabel.visibility = if (next == "IMAGE") View.GONE else View.VISIBLE
        previousPage.visibility = if (next == "IMAGE") View.GONE else View.VISIBLE
        nextPage.visibility = if (next == "IMAGE") View.GONE else View.VISIBLE
        if (next == "IMAGE") {
            data.text = "Image loaded: $imageName"
            status.text = "$imageName • ${bitmap?.width} × ${bitmap?.height} • ${bytes.size} bytes"
        } else {
            renderDataPage()
        }
    }

    private fun changePage(direction: Int) {
        val bytes = imageBytes ?: return
        val lastOffset = ((bytes.size - 1).coerceAtLeast(0) / pageSize) * pageSize
        byteOffset = (byteOffset + direction * pageSize).coerceIn(0, lastOffset)
        renderDataPage()
    }

    private fun renderDataPage() {
        val bytes = imageBytes ?: return
        val end = min(byteOffset + pageSize, bytes.size)
        val page = bytes.copyOfRange(byteOffset, end)
        data.text = when (mode) {
            "BINARY" -> page.mapIndexed { i, b ->
                (if (i % 16 == 0) "\n%08X  ".format(byteOffset + i) else "") +
                    b.toInt().and(255).toString(2).padStart(8, '0')
            }.joinToString(" ")
            "HEX" -> page.mapIndexed { i, b ->
                (if (i % 16 == 0) "\n%08X  ".format(byteOffset + i) else "") +
                    "%02X".format(b.toInt() and 255)
            }.joinToString(" ")
            "ASCII" -> page.mapIndexed { i, b ->
                (if (i % 64 == 0) "\n%08X  ".format(byteOffset + i) else "") +
                    (b.toInt().and(255).let { if (it in 32..126) it.toChar() else '.' })
            }.joinToString("")
            else -> ""
        }
        val pageNumber = byteOffset / pageSize + 1
        val totalPages = ((bytes.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        pageLabel.text = "Page $pageNumber / $totalPages • bytes $byteOffset–${(end - 1).coerceAtLeast(byteOffset)}"
        previousPage.isEnabled = byteOffset > 0
        nextPage.isEnabled = end < bytes.size
        status.text = "$mode • full file: ${bytes.size} bytes • showing $byteOffset–${end - 1}"
    }

    private fun loadImage(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read image")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unsupported image")
            imageBytes = bytes; bitmap = bmp
            imageName = queryName(uri)
            viewer.setBitmap(bmp)
            status.text = "$imageName • ${bmp.width} × ${bmp.height} • ${bytes.size} bytes"
            info.text = "Tap a pixel to inspect RGB / HEX / Binary"
            setMode("IMAGE")
        } catch (e: Exception) { status.text = "Could not open image: ${e.message}" }
    }

    private fun queryName(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else "Image"
        } ?: "Image"
    }

    private fun showPixel(x: Int, y: Int, color: Int) {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color); val a = Color.alpha(color)
        val hex = "#%02X%02X%02X".format(r, g, b)
        info.text = "X: $x   Y: $y   •   RGBA: $r, $g, $b, $a\nHEX: $hex\nR: ${bin(r)}  G: ${bin(g)}  B: ${bin(b)}"
    }

    private fun bin(v: Int) = v.toString(2).padStart(8, '0')
    private fun copyText(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PixelZoom data", s))
        status.text = "Copied to clipboard"
    }
    private fun copyPageBytes() {
        val bytes = imageBytes ?: run { status.text = "No file loaded"; return }
        val end = min(byteOffset + pageSize, bytes.size)
        val page = bytes.copyOfRange(byteOffset, end)
        copyText(page.joinToString("") { "%02X".format(it.toInt() and 255) })
    }

    private fun checkUpdate(silent: Boolean) {
        status.text = "Checking GitHub Releases…"
        thread {
            try {
                val c = URL(repoApi).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PixelZoom-Android")
                c.connectTimeout = 10000; c.readTimeout = 10000
                if (c.responseCode == 404) { c.disconnect(); runOnUiThread { status.text = "No GitHub Release published yet" }; return@thread }
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
                val tag = json.optString("tag_name", "")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", true)) { apkUrl = asset.optString("browser_download_url"); break }
                }
                runOnUiThread {
                    if (apkUrl != null && tag.isNotBlank() && tag.removePrefix("v") != BuildConfig.VERSION_NAME) {
                        status.text = "Update available: $tag"
                        AlertDialog.Builder(this).setTitle("PixelZoom update available")
                            .setMessage("$tag\n\n${json.optString("body", "A new version is ready.")}")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Download") { _, _ -> downloadUpdate(apkUrl!!) }.show()
                    } else {
                        status.text = if (silent) "PixelZoom is ready • no newer release found" else "You're using the latest published version"
                    }
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Update check failed: check internet connection" } }
        }
    }

    private fun downloadUpdate(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("PixelZoom update").setDescription("Downloading APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "pixelzoom-update.apk")
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                    try {
                        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pixelzoom-update.apk")
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
                        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(install)
                    } catch (e: Exception) { status.text = "APK downloaded, but installer could not open: ${e.message}" }
                    unregisterReceiver(this)
                }
            }
            ContextCompat.registerReceiver(this, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
            status.text = "Update download started • check notification for progress"
        } catch (e: Exception) { status.text = "Download failed: ${e.message}" }
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(Color.rgb(176, 190, 214))
        setPadding(dp(2), dp(8), dp(2), dp(8))
    }
    private fun action(s: String, click: () -> Unit) = Button(this).apply {
        text = s; textSize = 10f; isAllCaps = false
        setOnClickListener { click() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun LinearParams(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    class PixelCanvas(context: Context, val onPixel: (Int, Int, Int) -> Unit) : View(context) {
        private var bmp: Bitmap? = null
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val gridPaint = Paint().apply { color = 0x668B9CFF; strokeWidth = 1f }
        private val markerPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f }
        private var scale = 1f
        private var lastX = 0f; private var lastY = 0f
        private var selectedX = -1; private var selectedY = -1
        private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val factor = d.scaleFactor
                scale = (scale * factor).coerceIn(0.5f, 1200f)
                matrix.postScale(factor, factor, d.focusX, d.focusY)
                invalidate(); return true
            }
        })
        fun setBitmap(b: Bitmap) {
            bmp = b; selectedX = -1; selectedY = -1
            val fit = min(width.toFloat() / b.width.coerceAtLeast(1), height.toFloat() / b.height.coerceAtLeast(1)).coerceAtLeast(0.01f)
            scale = fit
            matrix.setScale(fit, fit)
            matrix.postTranslate((width - b.width * fit) / 2f, (height - b.height * fit) / 2f)
            invalidate()
        }
        fun resetZoom() { bmp?.let { setBitmap(it) } }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { bmp?.let { setBitmap(it) } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(14, 18, 28))
            val b = bmp ?: return
            canvas.drawBitmap(b, matrix, paint)
            if (scale >= 8f) {
                val inv = Matrix(); if (matrix.invert(inv)) {
                    val pts = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat()); inv.mapPoints(pts)
                    val left = max(0, pts[0].toInt()); val top = max(0, pts[1].toInt())
                    val right = min(b.width, pts[2].toInt() + 2); val bottom = min(b.height, pts[3].toInt() + 2)
                    if ((right - left) * (bottom - top) < 20000) {
                        for (x in left..right) { val p = floatArrayOf(x.toFloat(), top.toFloat(), x.toFloat(), bottom.toFloat()); matrix.mapPoints(p); canvas.drawLine(p[0], p[1], p[2], p[3], gridPaint) }
                        for (y in top..bottom) { val p = floatArrayOf(left.toFloat(), y.toFloat(), right.toFloat(), y.toFloat()); matrix.mapPoints(p); canvas.drawLine(p[0], p[1], p[2], p[3], gridPaint) }
                    }
                }
            }
            if (selectedX >= 0) {
                val p = floatArrayOf(selectedX.toFloat(), selectedY.toFloat(), (selectedX + 1).toFloat(), (selectedY + 1).toFloat())
                matrix.mapPoints(p); canvas.drawRect(p[0], p[1], p[2], p[3], markerPaint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
                MotionEvent.ACTION_MOVE -> if (!detector.isInProgress && event.pointerCount == 1) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY); lastX = event.x; lastY = event.y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (event.eventTime - event.downTime < 250 && kotlin.math.abs(event.x - lastX) < 4f) {
                        val inv = Matrix(); if (matrix.invert(inv)) {
                            val p = floatArrayOf(event.x, event.y); inv.mapPoints(p)
                            val b = bmp
                            if (b != null && p[0] >= 0 && p[1] >= 0 && p[0] < b.width && p[1] < b.height) {
                                selectedX = p[0].toInt(); selectedY = p[1].toInt()
                                onPixel(selectedX, selectedY, b.getPixel(selectedX, selectedY)); invalidate()
                            }
                        }
                    }
                }
            }
            return true
        }
    }
}
