package dev.folio.feature.editor.state

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.common.getOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImageImportTest {
    private fun encoded(
        format: Bitmap.CompressFormat,
        w: Int = 60,
        h: Int = 40,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF16A34A.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    @Test
    fun fromBytes_pngAndJpeg_keptByteForByte() {
        val png = encoded(Bitmap.CompressFormat.PNG)
        val jpeg = encoded(Bitmap.CompressFormat.JPEG)

        val a = ImageImport.fromBytes(png, "a.png").getOrNull()!!
        val b = ImageImport.fromBytes(jpeg, "b.jpg").getOrNull()!!

        assertThat(a.bytes).isSameInstanceAs(png)
        assertThat(a.mime).isEqualTo("image/png")
        assertThat(b.bytes).isSameInstanceAs(jpeg)
        assertThat(b.mime).isEqualTo("image/jpeg")
        assertThat(b.rotationDeg).isEqualTo(0f) // no EXIF orientation
        assertThat(a.widthPx to a.heightPx).isEqualTo(60 to 40)
    }

    @Test
    fun fromBytes_webp_convertedToPngWithItsSizeAndName() {
        val image = ImageImport.fromBytes(encoded(Bitmap.CompressFormat.WEBP_LOSSLESS), "shot.webp").getOrNull()!!

        assertThat(image.mime).isEqualTo("image/png")
        assertThat(image.originalName).isEqualTo("shot.png")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        assertThat(bounds.outMimeType).isEqualTo("image/png")
        assertThat(bounds.outWidth to bounds.outHeight).isEqualTo(60 to 40)
    }

    @Test
    fun fromBytes_notAnImage_fails() {
        assertThat(ImageImport.fromBytes(byteArrayOf(1, 2, 3), "x.bin")).isInstanceOf(Outcome.Failure::class.java)
    }
}
