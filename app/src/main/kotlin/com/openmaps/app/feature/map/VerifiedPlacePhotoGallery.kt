package com.openmaps.app.feature.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.mapSheetSurfaceColor
import com.openmaps.app.core.designsystem.mapSheetMutedColor
import com.openmaps.app.core.designsystem.mapSheetPrimaryTextColor
import com.openmaps.app.core.designsystem.mapSheetSecondaryTextColor
import com.openmaps.app.core.designsystem.mapSheetBorderColor
import com.openmaps.app.core.geocoding.GalleryPhoto
import com.openmaps.app.core.geocoding.GalleryPhotoKind
import com.openmaps.app.core.geocoding.PlacePhotoGallery

/** Place photographs and nearby streets are deliberately kept in different sections. */
@Composable
internal fun VerifiedPlacePhotoGallery(
    gallery: PlacePhotoGallery,
    loading: Boolean,
    streetSourceConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<GalleryPhoto?>(null) }
    val allPhotos = gallery.destinationPhotos + gallery.nearbyStreetViews
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Destination photos", style = MaterialTheme.typography.titleMedium,
            color = mapSheetPrimaryTextColor())
        when {
            loading -> Text("Finding licensed photos…", color = mapSheetSecondaryTextColor())
            gallery.destinationPhotos.isEmpty() ->
                Text("No verified photos of this destination have been found yet.",
                    color = mapSheetSecondaryTextColor(), style = MaterialTheme.typography.bodyMedium)
            else -> PhotoTiles(gallery.destinationPhotos) { selected = it }
        }
        Text("Nearby street views", style = MaterialTheme.typography.titleMedium,
            color = mapSheetPrimaryTextColor())
        when {
            loading -> Text("Checking nearby street imagery…", color = mapSheetSecondaryTextColor())
            gallery.nearbyStreetViews.isNotEmpty() ->
                PhotoTiles(gallery.nearbyStreetViews) { selected = it }
            !streetSourceConfigured ->
                Text("Nearby street views are optional and need a Mapillary token. Destination photos use Wikimedia Commons and need no token.",
                    color = mapSheetSecondaryTextColor(), style = MaterialTheme.typography.bodySmall)
            else -> Text("No street-level photos found close to this location.",
                color = mapSheetSecondaryTextColor(), style = MaterialTheme.typography.bodySmall)
        }
        Text("Nearby street photos may not show the destination itself.",
            style = MaterialTheme.typography.labelSmall, color = mapSheetSecondaryTextColor())
    }
    selected?.let { photo ->
        Dialog(
            onDismissRequest = { selected = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(OpenMapsColors.Navy950).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (photo.kind == GalleryPhotoKind.Destination) "Destination photo"
                        else "Nearby street view", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selected = null }) { Text("Close", color = Color.White) }
                }
                Image(
                    bitmap = photo.bitmap.asImageBitmap(),
                    contentDescription = if (photo.kind == GalleryPhotoKind.Destination)
                        "Photo of selected destination" else "Nearby street-level photograph",
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentScale = ContentScale.Fit,
                )
                Text("Photo: " + photo.author + " · " + photo.license,
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val i = allPhotos.indexOf(photo)
                        if (i > 0) selected = allPhotos[i - 1]
                    }, enabled = allPhotos.indexOf(photo) > 0) { Text("Previous") }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.sourceUrl)))
                        }
                    }) { Text("Credits & license") }
                    TextButton(onClick = {
                        val i = allPhotos.indexOf(photo)
                        if (i >= 0 && i < allPhotos.lastIndex) selected = allPhotos[i + 1]
                    }, enabled = allPhotos.indexOf(photo) >= 0 &&
                        allPhotos.indexOf(photo) < allPhotos.lastIndex) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun PhotoTiles(photos: List<GalleryPhoto>, onSelect: (GalleryPhoto) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        photos.chunked(3).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                group.forEach { photo ->
                    Image(
                        bitmap = photo.bitmap.asImageBitmap(),
                        contentDescription = "View photo by " + photo.author,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).height(96.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(photo) },
                    )
                }
                repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
