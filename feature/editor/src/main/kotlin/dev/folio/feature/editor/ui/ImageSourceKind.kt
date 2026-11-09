package dev.folio.feature.editor.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.folio.core.common.FolioLog
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.feature.editor.canvas.SelectionImage
import dev.folio.feature.editor.state.EditorViewModel
import java.io.File

/**
 * Where the options row inserts from (10-editor-ui.md#tool-options): the Image tool's [GALLERY] and [CAMERA], the
 * Attachment tool's [FILE] picker (A-070).
 */
enum class ImageSourceKind { GALLERY, CAMERA, FILE }

// Gallery | Camera: each inserts a picture into the page on screen (P07-T04).
@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
internal fun ImageOptionsContent(
    onImage: (ImageSourceKind) -> Unit,
    vertical: Boolean,
) {
    if (vertical) {
        FolioIconButton(FolioIcons.Image, "Insert from gallery", onClick = { onImage(ImageSourceKind.GALLERY) })
        FolioIconButton(FolioIcons.Camera, "Take a photo", onClick = { onImage(ImageSourceKind.CAMERA) })
    } else {
        FolioButton(label = "Gallery", onClick = { onImage(ImageSourceKind.GALLERY) }, style = FolioButtonStyle.Text)
        PillDivider(vertical = false)
        FolioButton(label = "Camera", onClick = { onImage(ImageSourceKind.CAMERA) }, style = FolioButtonStyle.Text)
    }
}

// Attach file: the one button of the Attachment tool's row 2 (A-070).
@Composable
internal fun AttachmentOptionsContent(
    onImage: (ImageSourceKind) -> Unit,
    vertical: Boolean,
) {
    if (vertical) {
        FolioIconButton(FolioIcons.Paperclip, "Attach file", onClick = { onImage(ImageSourceKind.FILE) })
    } else {
        FolioButton(label = "Attach file", onClick = { onImage(ImageSourceKind.FILE) }, style = FolioButtonStyle.Text)
    }
}

// The system photo picker, the camera app writing into our cache (no CAMERA permission), or the system file picker for
// attachments; [notice] shows a snackbar.
@Composable
internal fun rememberImageSources(
    viewModel: EditorViewModel,
    notice: (String) -> Unit,
): (ImageSourceKind) -> Unit {
    val context = LocalContext.current
    val galleryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.insertImage(context.contentResolver, uri)
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.insertAttachment(context.contentResolver, uri)
        }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = cameraUri
            if (taken && uri != null) viewModel.insertImage(context.contentResolver, uri)
        }
    return { kind ->
        when (kind) {
            ImageSourceKind.GALLERY -> {
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            ImageSourceKind.CAMERA -> {
                val uri = CameraCapture.newUri(context)
                cameraUri = uri
                try {
                    camera.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    FolioLog.w("ImageSources", "no camera app: ${e.message}")
                    notice("No camera app")
                }
            }

            ImageSourceKind.FILE -> {
                filePicker.launch(arrayOf("*/*"))
            }
        }
    }
}

/**
 * Opens [file] (an attachment written to the cache) in another app through the editor FileProvider, inside the system
 * chooser (A-070); false when even the chooser cannot start.
 */
internal fun openAttachmentFile(
    context: Context,
    file: File,
    mime: String,
): Boolean {
    val uri = FileProvider.getUriForFile(context, SelectionImage.authority(context), file)
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    view.clipData = ClipData.newRawUri(file.name, uri)
    // No resolveActivity check: without <queries> it hides most apps; the chooser itself says when none fits.
    return try {
        context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: ActivityNotFoundException) {
        FolioLog.w("AttachmentOpen", "no viewer: ${e.message}")
        false
    }
}
