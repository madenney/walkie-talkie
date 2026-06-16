package com.walkietalkie.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.walkietalkie.data.files.FileBrowserClient
import com.walkietalkie.data.files.FsEntry
import kotlinx.coroutines.launch
import java.io.File

/** A small full-screen image being previewed in-app. */
private data class ImagePreview(val bitmap: androidx.compose.ui.graphics.ImageBitmap, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    serverBaseUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(serverBaseUrl) { FileBrowserClient(serverBaseUrl) }

    // Navigation: a stack of folder paths. Empty = the roots (one per project).
    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    val currentPath = stack.lastOrNull()

    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImagePreview?>(null) }

    // (Re)load whenever the folder changes.
    LaunchedEffect(currentPath) {
        loading = true
        error = null
        try {
            entries = if (currentPath == null) {
                client.roots().map { FsEntry(name = it.name, path = it.path, isDir = true) }
            } else {
                client.list(currentPath).entries
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server"
            entries = emptyList()
        } finally {
            loading = false
        }
    }

    // Back: close a preview, else go up a folder, else leave the screen.
    BackHandler {
        when {
            preview != null -> preview = null
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            else -> onBack()
        }
    }

    fun openFile(entry: FsEntry) {
        scope.launch {
            try {
                if (entry.mime.startsWith("image/")) {
                    val bytes = client.readBytes(entry.path)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        preview = ImagePreview(bmp.asImageBitmap(), entry.name)
                    } else {
                        error = "Couldn't decode ${entry.name}"
                    }
                } else {
                    // Download to cache and hand off to whatever app can open it.
                    val dest = File(context.cacheDir, entry.name)
                    client.download(entry.path, dest)
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", dest
                    )
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, entry.mime.ifBlank { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(view, "Open ${entry.name}"))
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't open ${entry.name}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        text = currentPath?.substringAfterLast('/') ?: "Files",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && entries.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error != null && entries.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                entries.isEmpty() -> {
                    Text("Empty folder", Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { entry ->
                            FileRow(entry) {
                                if (entry.isDir) stack = stack + entry.path else openFile(entry)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    preview?.let { p ->
        ImageViewer(preview = p, onDismiss = { preview = null })
    }
}

@Composable
private fun FileRow(entry: FsEntry, onClick: () -> Unit) {
    val icon = when {
        entry.isDir -> Icons.Default.Folder
        entry.mime.startsWith("image/") -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            if (!entry.isDir) {
                Text(formatSize(entry.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Full-screen, pinch-to-zoom image preview. Tap the backdrop to dismiss. */
@Composable
private fun ImageViewer(preview: ImagePreview, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset += panChange
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = preview.bitmap,
            contentDescription = preview.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .transformable(transform),
        )
        Text(
            text = preview.name,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
