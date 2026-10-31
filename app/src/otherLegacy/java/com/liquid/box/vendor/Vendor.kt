package com.liquid.box.vendor

import androidx.camera.core.ImageAnalysis
import com.liquid.box.compose.screen.qrscan.QRCodeCropArea

object Vendor : VendorInterface {
    override fun createQRCodeAnalyzer(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        onCropArea: ((QRCodeCropArea?) -> Unit)?,
    ): ImageAnalysis.Analyzer? = null
}
