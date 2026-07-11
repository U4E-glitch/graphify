package com.dheyab.qiyas.data.photo

import android.graphics.Bitmap
import com.dheyab.qiyas.domain.ocr.OcrToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR over a meter photo (ML Kit, bundled model — no network).
 * Produces positioned tokens for the pure-Kotlin [MeterValueParser].
 */
@Singleton
class MeterScanner @Inject constructor() {

    suspend fun recognize(bitmap: Bitmap): List<OcrToken> = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val tokens = text.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        OcrToken(
                            text = line.text,
                            top = line.boundingBox?.top ?: 0,
                            height = line.boundingBox?.height() ?: 0,
                        )
                    }
                }
                cont.resume(tokens)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }
}
