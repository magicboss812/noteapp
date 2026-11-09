package dev.folio.feature.editor.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.folio.feature.editor.canvas.SelectionImage
import java.io.File

/**
 * Where the camera app writes a photo for the Image tool (P07-T04): one file in the app cache, shared through the
 * editor's FileProvider (`camera/` path), overwritten by the next capture; the note stores its own copy.
 */
internal object CameraCapture {
    private const val DIR = "camera"
    private const val FILE = "capture.jpg"

    /** A writable content Uri for the next capture. */
    fun newUri(context: Context): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return FileProvider.getUriForFile(context, SelectionImage.authority(context), File(dir, FILE))
    }
}
