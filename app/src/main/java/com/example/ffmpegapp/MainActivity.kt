package com.example.ffmpegapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.style.ExperimentalFoundationStyleApi
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ffmpegapp.ui.main.MainScreenViewModel
import com.example.ffmpegapp.data.DefaultDataRepository

@OptIn(ExperimentalFoundationStyleApi::class)
object ComponentStyles {
    val inputFieldStyle = Style {
        background(Color(0xFFE0E0E0)) // Light gray background
        shape(RoundedCornerShape(8.dp))
    }
}

@OptIn(ExperimentalFoundationStyleApi::class)
@Composable
fun CustomTrimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }

    val styleState = rememberUpdatedStyleState(interactionSource) {}
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .styleable(styleState, ComponentStyles.inputFieldStyle, Style)
                .padding(16.dp),
            textStyle = TextStyle(color = if (enabled) Color.Black else Color.Gray)
        )
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainScreenViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = androidx.lifecycle.ViewModelProvider(this, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MainScreenViewModel(com.example.ffmpegapp.data.DefaultDataRepository(applicationContext)) as T
            }
        })[MainScreenViewModel::class.java]

        handleSendIntent(intent)

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FFmpegScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        handleSendIntent(newIntent)
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) {
                viewModel.setSharedInputUri(uri)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FFmpegScreen(viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    val outputUri by viewModel.outputDirectoryUri.collectAsStateWithLifecycle()
    var inputFileName by remember { mutableStateOf<String?>(null) }
    val outputFileName = outputUri?.let { DocumentFile.fromSingleUri(context, it)?.name }

    var trimStartTime by remember { mutableStateOf("") }
    var trimEndTime by remember { mutableStateOf("") }
    var isTrimExpanded by remember { mutableStateOf(false) }

    var statusText by remember { mutableStateOf("Idle") }
    var isProcessing by remember { mutableStateOf(false) }
    var mediaInfoText by remember { mutableStateOf("") }
    var mediaDuration by remember { mutableStateOf(0f) }
    var currentProgress by remember { mutableStateOf(0f) }

    val sharedInputUri by viewModel.sharedInputUri.collectAsStateWithLifecycle()

    LaunchedEffect(sharedInputUri) {
        val uri = sharedInputUri
        inputUri = uri
        if (uri != null) {
            inputFileName = getPathFromUri(context, uri)
            coroutineScope.launch(Dispatchers.IO) {
                mediaInfoText = "Extracting media info..."
                val inputSafUrl = FFmpegKitConfig.getSafParameterForRead(context, uri)
                val session = FFprobeKit.getMediaInformation(inputSafUrl)
                val info = session.getMediaInformation()
                if (info != null) {
                    val durationStr = info.getDuration()
                    val durationFloat = durationStr?.toFloatOrNull()
                    val durationText = if (durationFloat != null) {
                        mediaDuration = durationFloat
                        formatDuration(durationFloat)
                    } else {
                        mediaDuration = 0f
                        "N/A"
                    }

                    val formatStr = info.getFormat()
                    val formatText = formatStr?.split(",")?.firstOrNull()?.uppercase() ?: "N/A"

                    val text = StringBuilder()
                    text.appendLine("Duration: $durationText")
                    text.appendLine("Format: $formatText")

                    val streams = info.getStreams()
                    var videoStreamText = ""
                    var audioStreamText = ""

                    streams?.forEach { stream ->
                        if (stream.getType() == "video") {
                            var codec = stream.getCodec()?.uppercase() ?: "Unknown"
                            if (codec == "H264") codec = "H.264"
                            val width = stream.getWidth() ?: "?"
                            val height = stream.getHeight() ?: "?"
                            val bitrate = formatBitrate(stream.getBitrate())
                            val colorSpace = stream.getStringProperty("color_space")

                            val components = mutableListOf<String>()
                            components.add("$codec (${width}x${height})")
                            if (bitrate != null) components.add(bitrate)
                            if (colorSpace != null) components.add(colorSpace)

                            videoStreamText = components.joinToString(" • ")
                        } else if (stream.getType() == "audio") {
                            val codec = stream.getCodec()?.uppercase() ?: "Unknown"
                            val bitrate = formatBitrate(stream.getBitrate())

                            val components = mutableListOf<String>()
                            components.add(codec)
                            if (bitrate != null) components.add(bitrate)

                            audioStreamText = components.joinToString(" • ")
                        }
                    }
                    if (videoStreamText.isNotEmpty()) text.appendLine("Video: $videoStreamText")
                    if (audioStreamText.isNotEmpty()) text.appendLine("Audio: $audioStreamText")

                    mediaInfoText = text.toString().trim()
                } else {
                    mediaInfoText = "Failed to extract metadata."
                    Log.e("FFmpegApp", "FFprobe Failed: ${session.getFailStackTrace()}")
                }
            }
        } else {
            inputFileName = null
            mediaInfoText = ""
            mediaDuration = 0f
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.setSharedInputUri(uri)
    }

    val outputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        viewModel.onDirectorySelected(uri, context)
    }
    
    val canRun = inputUri != null && outputUri != null && !isProcessing
    val runFfmpegAction = {
        val currentInputUri = inputUri
        val currentOutputUri = outputUri
        if (currentInputUri != null && currentOutputUri != null) {
            isProcessing = true
            currentProgress = 0f
            statusText = "Processing..."
            val inputSafUrl = FFmpegKitConfig.getSafParameterForRead(context, currentInputUri)

            val outputSafUrl = FFmpegKitConfig.getSafParameterForWrite(context, currentOutputUri)
            val command = buildFfmpegCommand(trimStartTime, trimEndTime, inputSafUrl, outputSafUrl)

            FFmpegKit.executeAsync(
                command,
                { session ->
                    val returnCode = session.getReturnCode()
                    if (ReturnCode.isSuccess(returnCode)) {
                        statusText = "Completed"
                    } else if (ReturnCode.isCancel(returnCode)) {
                        statusText = "Cancelled"
                    } else {
                        statusText = "Failed (Code: ${returnCode?.value})"
                        Log.e("FFmpegApp", "FFmpeg process failed with rc: ${returnCode?.value}")
                        Log.e("FFmpegApp", "Fail Stack Trace: ${session.getFailStackTrace()}")
                        Log.e("FFmpegApp", "Output Logs: \n${session.getAllLogsAsString()}")
                    }
                    isProcessing = false
                },
                { log -> },
                { statistics ->
                    val timeInMilliseconds = statistics.time.toFloat()
                    if (timeInMilliseconds > 0f && mediaDuration > 0f) {
                        val timeInSeconds = timeInMilliseconds / 1000f
                        currentProgress = (timeInSeconds / mediaDuration).coerceIn(0f, 1f)
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("FFmpeg Video Trimmer") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (canRun) runFfmpegAction()
                },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Run") },
                text = { Text("Run FFmpeg") },
                expanded = true,
                containerColor = if (canRun) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (canRun) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Input Section
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = { inputLauncher.launch(arrayOf("video/*")) },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Input Video")
                    }
                    if (inputFileName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "File: $inputFileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. Media Info Section
            if (mediaInfoText.isNotEmpty()) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("File Information", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = mediaInfoText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 3. Trim Video Section
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) { isTrimExpanded = !isTrimExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Trim Video", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            imageVector = if (isTrimExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = isTrimExpanded) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CustomTrimTextField(
                                        value = trimStartTime,
                                        onValueChange = { if (!isProcessing) trimStartTime = it },
                                        label = "Start time:",
                                        enabled = !isProcessing
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    CustomTrimTextField(
                                        value = trimEndTime,
                                        onValueChange = { if (!isProcessing) trimEndTime = it },
                                        label = "End time:",
                                        enabled = !isProcessing
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Output Settings Section
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Output Settings", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { outputLauncher.launch("output.mp4") },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Save Location")
                    }
                    if (outputFileName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "File: $outputFileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Status & Progress Section
            if (isProcessing) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { currentProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Processing: ${(currentProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (statusText != "Idle") {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Status: $statusText",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // padding for the FAB at the bottom
        }
    }
}

fun buildFfmpegCommand(startTime: String, endTime: String, inputUrl: String, outputUrl: String): String {
    val ssPart = if (startTime.isNotBlank()) "-ss $startTime " else ""
    val toPart = if (endTime.isNotBlank()) "-to $endTime " else ""
    return "$ssPart$toPart-i $inputUrl -c:v libx264 -f mp4 -y $outputUrl"
}

fun formatDuration(durationSec: Float): String {
    val h = (durationSec / 3600).toInt()
    val m = ((durationSec % 3600) / 60).toInt()
    val s = durationSec % 60

    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%06.3f", h, m, s)
    } else {
        String.format(java.util.Locale.US, "%d:%06.3f", m, s)
    }
}

fun getPathFromUri(context: Context, uri: Uri): String {
    try {
        // 1. Handle Directory (Tree) URIs
        if (DocumentsContract.isTreeUri(uri)) {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val split = treeId.split(":")
            if (split.size >= 2 && split[0].equals("primary", true)) {
                return "Main Storage/" + split[1]
            }
            return treeId
        }

        // 2. Handle File (Document) URIs
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2 && split[0].equals("primary", true)) {
                return "Main Storage/" + split[1]
            }
        }

        // 3. Fallback for raw paths or basic content URIs
        val path = uri.path ?: return uri.toString()
        if (path.contains("/storage/emulated/0/")) {
            return path.replace(Regex(".*storage/emulated/0/"), "Main Storage/")
        }

        return uri.toString()
    } catch (e: Exception) {
        e.printStackTrace()
        return "Unknown Path"
    }
}

fun formatBitrate(bitrateStr: String?): String? {
    if (bitrateStr.isNullOrEmpty()) return null
    val bps = bitrateStr.toLongOrNull() ?: return null
    return if (bps >= 1_000_000) {
        String.format(java.util.Locale.US, "%.2f Mbps", bps / 1_000_000f)
    } else {
        String.format(java.util.Locale.US, "%.0f kbps", bps / 1000f)
    }
}
