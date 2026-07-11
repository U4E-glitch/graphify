package com.dheyab.qiyas.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-private photo storage for reading attachments. Photos never leave the
 * app sandbox (Hard Rule 7 — fully local data).
 */
@Singleton
class PhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 85
    }

    fun fileFor(relativePath: String): File = File(context.filesDir, relativePath)

    /** Loads a photo as a bitmap (EXIF-rotated), or null when missing/corrupt. */
    suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            applyExifRotation(bytes, downscale(bitmap))
        }.getOrNull()
    }

    /** Copies a picked/captured photo into private storage; returns its relative path. */
    suspend fun import(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val name = "photos/reading_${UUID.randomUUID()}.jpg"
            FileOutputStream(File(context.filesDir, name)).use { out ->
                downscale(bitmap).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            check(dir.exists())
            name
        }.getOrNull()
    }

    fun delete(relativePath: String?) {
        relativePath?.let { File(context.filesDir, it).delete() }
    }

    /** Content Uri + backing cache file for a camera capture. */
    fun newCaptureTarget(): Pair<Uri, File> {
        val captures = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(captures, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to file
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    private fun applyExifRotation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
