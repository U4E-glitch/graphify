package com.dheyab.qiyas.ui.entry

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dheyab.qiyas.R
import com.dheyab.qiyas.ui.theme.ZoneGreen
import com.dheyab.qiyas.ui.theme.ZoneYellow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Scan from photo" section (spec v1 photo/OCR input): capture or pick a meter
 * photo, run on-device OCR to pre-fill the fields, and attach the photo to the
 * reading. Scanned values are suggestions — the user reviews before saving.
 */
@Composable
fun PhotoSection(
    photoPath: String?,
    scanStatus: ScanStatus?,
    fileFor: (String) -> File,
    newCaptureTarget: () -> Pair<Uri, File>,
    onPhotoSelected: (Uri) -> Unit,
    onPhotoRemoved: () -> Unit,
) {
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var viewerOpen by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCaptureUri?.let(onPhotoSelected)
        pendingCaptureUri = null
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPhotoSelected) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.photo_label), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val (uri, _) = newCaptureTarget()
                    pendingCaptureUri = uri
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Text(stringResource(R.string.photo_scan_camera), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Text(stringResource(R.string.photo_scan_gallery), modifier = Modifier.padding(start = 6.dp))
            }
        }

        when (scanStatus) {
            ScanStatus.SCANNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.photo_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            ScanStatus.FILLED -> Text(
                stringResource(R.string.photo_filled),
                style = MaterialTheme.typography.bodySmall,
                color = ZoneGreen,
            )
            ScanStatus.VALUE_NOT_FOUND -> Text(
                stringResource(R.string.photo_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = ZoneYellow,
            )
            null -> Unit
        }

        photoPath?.let { path ->
            Box {
                PhotoThumbnail(
                    file = fileFor(path),
                    modifier = Modifier
                        .size(96.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { viewerOpen = true },
                )
                IconButton(
                    onClick = onPhotoRemoved,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.photo_remove),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (viewerOpen) {
                PhotoViewerDialog(file = fileFor(path), onDismiss = { viewerOpen = false })
            }
        }
    }
}

@Composable
fun PhotoThumbnail(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampled(file, 512) }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.photo_view),
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
fun PhotoViewerDialog(file: File, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

private fun decodeSampled(file: File, targetDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetDimension) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
