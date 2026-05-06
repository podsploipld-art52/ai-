package com.aiagent.android.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR using ML Kit Latin script text recognition.
 *
 * Returns the recognised text along with bounding boxes in a compact format suitable for the LLM.
 */
object OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        if (result.textBlocks.isEmpty()) return "(на экране нет распознаваемого текста)"
        val sb = StringBuilder()
        for ((blockIdx, block) in result.textBlocks.withIndex()) {
            val rect = block.boundingBox
            sb.append("--- блок ").append(blockIdx)
            if (rect != null) {
                sb.append(" @[")
                    .append(rect.left).append(',').append(rect.top).append('-')
                    .append(rect.right).append(',').append(rect.bottom).append(']')
            }
            sb.append(" ---\n")
            sb.append(block.text).append('\n')
        }
        return sb.toString()
    }
}
