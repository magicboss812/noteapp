package dev.folio.feature.editor.state

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads a picked or captured image (P07-T04, 04-file-format.md#assets): JPEG and PNG are kept byte for byte, anything
 * else that decodes (HEIC, WebP, ...) becomes PNG, or JPEG q92 above 4 MP. A JPEG's EXIF orientation becomes the
 * image's starting quarter turn, so the stored bytes stay the original's.
 */
internal object ImageImport {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val PHOTO_PIXELS = 4_000_000L
    private const val JPEG_QUALITY = 92
    private const val PNG_QUALITY = 100
    private const val JPEG = "image/jpeg"
    private const val PNG = "image/png"
    private const val TAG = "ImageImport"

    /** The image at [uri], or a failure when it cannot be read or decoded. */
    @WorkerThread
    fun read(
        resolver: ContentResolver,
        uri: Uri,
    ): Outcome<ImportedImage> =
        try {
            val bytes =
                resolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("image larger than $MAX_BYTES bytes")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IOException("no stream for $uri")
            fromBytes(bytes, displayName(resolver, uri))
        } catch (e: IOException) {
            FolioLog.w(TAG, "read failed: ${e.message}", e)
            Outcome.Failure("image not readable", e)
        } catch (e: SecurityException) {
            FolioLog.w(TAG, "read refused: ${e.message}", e)
            Outcome.Failure("image not readable", e)
        }

    /** [bytes] as an importable image named [name]; a failure when they do not decode. */
    fun fromBytes(
        bytes: ByteArray,
        name: String?,
    ): Outcome<ImportedImage> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return Outcome.Failure("not an image")
        return when (bounds.outMimeType) {
            JPEG -> Outcome.Success(ImportedImage(bytes, JPEG, name, w, h, exifRotationDeg(bytes)))
            PNG -> Outcome.Success(ImportedImage(bytes, PNG, name, w, h, 0f))
            else -> convert(bytes, name, w, h)
        }
    }

    private fun convert(
        bytes: ByteArray,
        name: String?,
        w: Int,
        h: Int,
    ): Outcome<ImportedImage> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return Outcome.Failure("image does not decode")
        val photo = w.toLong() * h > PHOTO_PIXELS
        val out = ByteArrayOutputStream()
        bitmap.compress(if (photo) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (photo) JPEG_QUALITY else PNG_QUALITY, out)
        bitmap.recycle()
        val base = name?.substringBeforeLast('.')
        val mime = if (photo) JPEG else PNG
        val renamed = base?.let { "$it.${if (photo) "jpg" else "png"}" }
        return Outcome.Success(ImportedImage(out.toByteArray(), mime, renamed, w, h, 0f))
    }

    /** The clockwise quarter turn of a JPEG's EXIF orientation (mirrored orientations: their turn only). */
    internal fun exifRotationDeg(bytes: ByteArray): Float =
        try {
            when (
                ExifInterface(
                    ByteArrayInputStream(bytes),
                ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> QUARTER
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> HALF
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> THREE_QUARTERS
                else -> 0f
            }
        } catch (e: IOException) {
            FolioLog.w(TAG, "no EXIF: ${e.message}", e)
            0f
        }

    private fun displayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private const val QUARTER = 90f
    private const val HALF = 180f
    private const val THREE_QUARTERS = 270f
}
