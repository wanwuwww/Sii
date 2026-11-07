// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.core.webview.QrCodeDecoder
import java.util.concurrent.Executors

/**
 * 相机扫码页：CameraX 预览 + ZXing 解码（帧先转 Bitmap 再按旋转角度矫正）、
 * 闪光灯开关、中央取景框、相册选图。识别成功后通过 [EXTRA_TEXT] 返回文本。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var torchButton: android.widget.ImageButton
    private val executor = Executors.newSingleThreadExecutor()
    private var handled = false
    private var analyzing = false
    private var camera: Camera? = null
    private var torchOn = false

    /** 从相册选图解码。 */
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bitmap = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) > MAX_DECODE_SIDE ||
                bounds.outHeight / (sample * 2) > MAX_DECODE_SIDE
            ) {
                sample *= 2
            }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
        val text = bitmap?.let { QrCodeDecoder.decode(scaleDown(it, MAX_DECODE_SIDE)) }
        if (text.isNullOrBlank()) {
            android.widget.Toast.makeText(this, R.string.scan_failed, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            finishWith(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)
        root.addView(
            ScanOverlayView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 提示文案：取景框下方
        root.addView(TextView(this).apply {
            text = getString(R.string.scan_hint)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { topMargin = dp(260) }
        })

        // 左上角关闭
        root.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { marginStart = dp(8); topMargin = dp(8) }
        })

        // 底部操作行：相册选图 / 闪光灯
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(48) }
        }
        actions.addView(pillButton(getString(R.string.scan_from_gallery), R.drawable.ic_m_gallery) {
            pickImage.launch(arrayOf("image/*"))
        })
        torchButton = pillButton(getString(R.string.scan_torch_off), R.drawable.ic_m_torch) { toggleTorch() }
        actions.addView(torchButton)
        actions.addView(pillButton(getString(R.string.scan_generate), R.drawable.ic_m_scan) {
            showGenerateQrDialog()
        })
        root.addView(actions)

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    /** 从文本生成二维码并保存到相册（Pictures/Sii）。 */
    private fun showGenerateQrDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.scan_generate_hint)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.scan_generate)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                runCatching { saveQrBitmap(text) }
                    .onSuccess {
                        android.widget.Toast.makeText(
                            this, R.string.scan_generate_saved, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure {
                        android.widget.Toast.makeText(
                            this, R.string.scan_generate_failed, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveQrBitmap(text: String) {
        val size = 800
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size
        )
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) black else white)
            }
        }
        val name = "Sii_QR_${System.currentTimeMillis()}.png"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sii")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("insert failed")
            contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("open failed")
        } else {
            val dir = java.io.File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Sii"
            ).apply { mkdirs() }
            val file = java.io.File(dir, name)
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /** 底部圆形图标按钮：半透明黑底白描边，取景界面上清晰可点。 */
    private fun pillButton(label: String, icon: Int, onClick: () -> Unit): android.widget.ImageButton {
        val px = (44 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        return android.widget.ImageButton(this).apply {
            contentDescription = label
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundResource(R.drawable.bg_scan_button)
            layoutParams = LinearLayout.LayoutParams(px, px).apply {
                marginStart = margin
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            android.widget.Toast.makeText(this, R.string.scan_permission_denied, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                analysis.setAnalyzer(executor) { image -> analyze(image) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure {
                android.widget.Toast.makeText(this, R.string.scan_failed, android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val control = camera?.cameraControl ?: return
        torchOn = !torchOn
        control.enableTorch(torchOn)
        // 开启态用主题色区分，白色为关闭。
        torchButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (torchOn) 0xFF4CC2FF.toInt() else Color.WHITE
        )
    }

    private fun analyze(image: ImageProxy) {
        if (analyzing || handled) {
            image.close()
            return
        }
        analyzing = true
        try {
            // 统一走 Bitmap 解码路径（与相册选图一致），再按帧旋转角矫正
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val rotated = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            val text = QrCodeDecoder.decode(scaleDown(rotated, MAX_DECODE_SIDE))
            if (!text.isNullOrBlank()) {
                handled = true
                runOnUiThread { finishWith(text) }
            }
        } catch (_: Throwable) {
            // 单帧失败忽略，继续下一帧
        } finally {
            analyzing = false
            image.close()
        }
    }

    /** 限制解码位图边长，提升每帧速度。 */
    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun finishWith(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        finish()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    /** 中央取景框：四角括号 + 四周半透明遮罩。 */
    private class ScanOverlayView(context: android.content.Context) : View(context) {
        private val dimPaint = Paint().apply { color = 0x55000000; style = Paint.Style.FILL }
        private val framePaint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height) * 0.62f
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            val right = left + size
            val bottom = top + size
            canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, top, left, bottom, dimPaint)
            canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)
            val len = size * 0.14f
            canvas.drawLine(left, top, left + len, top, framePaint)
            canvas.drawLine(left, top, left, top + len, framePaint)
            canvas.drawLine(right, top, right - len, top, framePaint)
            canvas.drawLine(right, top, right, top + len, framePaint)
            canvas.drawLine(left, bottom, left + len, bottom, framePaint)
            canvas.drawLine(left, bottom, left, bottom - len, framePaint)
            canvas.drawLine(right, bottom, right - len, bottom, framePaint)
            canvas.drawLine(right, bottom, right, bottom - len, framePaint)
        }
    }

    companion object {
        const val EXTRA_TEXT = "scan_text"
        private const val REQ_CAMERA = 300
        private const val MAX_DECODE_SIDE = 1280
    }
}
