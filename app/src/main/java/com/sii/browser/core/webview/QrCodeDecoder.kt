// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码/条码解码（ZXing core，纯 Java，无 JNI）。
 * 相机帧（YUV）与图片（Bitmap）两条路径共用同一套解码器。
 */
object QrCodeDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.EAN_13
        ),
        DecodeHintType.TRY_HARDER to true
    )

    /** 解码图片。 */
    fun decode(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    /** 解码相机帧（Y 平面亮度数据）。 */
    fun decodeYuv(data: ByteArray, width: Int, height: Int): String? = runCatching {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
