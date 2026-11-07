package com.karen.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.utils.SourceEntry
import java.io.File

/**
 * Paginated artifact viewer + Sources sheet (OBSERVATION_STEPS clone notes).
 *
 * [PdfViewerDialog] renders pages with [PdfRenderer] (framework, no deps),
 * paginates with prev/next, and offers Download/Share via [MagazineBuilder].
 * [SourcesSheet] lists per-source cards behind the "N sources" chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerDialog(
    file: File,
    title: String,
    onDismiss: () -> Unit
) {
    val colors = LocalKarenColors.current
    val ctx = LocalContext.current
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file, pageIndex) {
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            pageCount = renderer.pageCount
            val idx = pageIndex.coerceIn(0, renderer.pageCount - 1)
            val page = renderer.openPage(idx)
            val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bmp, android.graphics.Rect(0, 0, page.width, page.height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            fd.close()
            bitmap = bmp
            error = null
        } catch (e: Exception) {
            error = "Could not render this page on this device (${e.message ?: e.javaClass.simpleName})."
            bitmap = null
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.96f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (pageCount > 0) {
                            Text(
                                "Page ${pageIndex + 1} of $pageCount",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close viewer", tint = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        error != null -> Text(error!!, color = colors.accentRed, fontSize = 12.sp)
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "PDF page ${pageIndex + 1}",
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> CircularProgressIndicator(color = colors.accentGreen)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                    enabled = pageIndex > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous page", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp)); Text("Prev")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        MagazineBuilder.downloadIntent(ctx, file)?.let { ctx.startActivity(it) }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("Download")
                    }
                    TextButton(onClick = {
                        MagazineBuilder.shareIntent(ctx, file)?.let { ctx.startActivity(Intent.createChooser(it, "Share ${file.name}")) }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("Share")
                    }
                }
                TextButton(
                    onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
                    enabled = pageIndex < pageCount - 1
                ) {
                    Text("Next"); Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page", modifier = Modifier.size(14.dp))
                }
            }
            }
        }
    }
}

/** Sources sheet: per-source cards behind the "N sources" chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(
    sources: List<SourceEntry>,
    onDismiss: () -> Unit
) {
    val colors = LocalKarenColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("${sources.size} source${if (sources.size == 1) "" else "s"}", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("What Karen consulted for this answer.", color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sources, key = { it.url + it.title }) { s ->
                SourceCard(s)
            }
        }
    }
}

@Composable
private fun SourceCard(s: SourceEntry) {
    val colors = LocalKarenColors.current
    val ctx = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceHover.copy(alpha = 0.4f))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(enabled = s.url.isNotBlank()) { runCatching { uriHandler.openUri(s.url) } }
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.accentBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (s.source.firstOrNull()?.uppercase() ?: "S"),
                color = colors.accentBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(s.source, color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            if (s.url.isNotBlank()) {
                Text(
                    s.url,
                    color = colors.accentBlue,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}
