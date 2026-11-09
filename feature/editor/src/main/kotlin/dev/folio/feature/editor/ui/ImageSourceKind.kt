package dev.folio.feature.editor.ui

import android.content.ActivityNotFoundException
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
import dev.folio.core.common.FolioLog
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.feature.editor.state.EditorViewModel

/** Where the Image tool takes a picture from (10-editor-ui.md#tool-options). */
enum class ImageSourceKind { GALLERY, CAMERA }

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

// The system photo picker, or the camera app writing into our cache (no CAMERA permission); [notice] shows a snackbar.
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
        }
    }
}
