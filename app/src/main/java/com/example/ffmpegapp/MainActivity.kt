package com.example.ffmpegapp

import android.content.Context
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
    label: String
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .styleable(styleState, ComponentStyles.inputFieldStyle, Style)
                .padding(16.dp),
            textStyle = TextStyle(color = Color.Black)
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FFmpegScreen()
                }
            }
        }
    }
}

@Composable
fun FFmpegScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var inputFileName by remember { mutableStateOf<String?>(null) }
    var outputFileName by remember { mutableStateOf<String?>(null) }
    var userTypedFilename by remember { mutableStateOf("output.mp4") }
    
    var trimStartTime by remember { mutableStateOf("") }
    var trimEndTime by remember { mutableStateOf("") }
    var isTrimExpanded by remember { mutableStateOf(false) }
    
    var statusText by remember { mutableStateOf("Idle") }
    var isProcessing by remember { mutableStateOf(false) }
    var mediaInfoText by remember { mutableStateOf("") }
    var isMediaInfoExpanded by remember { mutableStateOf(false) }
    var mediaDuration by remember { mutableStateOf(0f) }
    var currentProgress by remember { mutableStateOf(0f) }

    val inputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
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
                        String.format(java.util.Locale.US, "%.2f s", durationFloat)
                    } else {
                        mediaDuration = 0f
                        "N/A"
                    }

                    val formatStr = info.getFormat()
                    val formatText = formatStr?.split(",")?.firstOrNull()?.uppercase() ?: "N/A"

                    val text = StringBuilder()
                    text.appendLine("Duration: $durationText\n")
                    text.appendLine("Format: $formatText\n")
                    
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
                    if (videoStreamText.isNotEmpty()) text.appendLine("Video: $videoStreamText\n")
                    if (audioStreamText.isNotEmpty()) text.appendLine("Audio: $audioStreamText")

                    mediaInfoText = text.toString().trim()
                } else {
                    mediaInfoText = "Failed to extract metadata."
                    Log.e("FFmpegApp", "FFprobe Failed: ${session.getFailStackTrace()}")
                }
            }
        } else {
            mediaInfoText = ""
        }
    }

    val outputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        outputUri = uri
        if (uri != null) {
            outputFileName = getPathFromUri(context, uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { inputLauncher.launch(arrayOf("video/*")) }) {
                Text("Select Input Video")
            }
            Text(
                text = inputFileName?.let { "File: $it" } ?: "No input selected",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            if (mediaInfoText.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable { isMediaInfoExpanded = !isMediaInfoExpanded }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("File Information", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isMediaInfoExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                AnimatedVisibility(visible = isMediaInfoExpanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = mediaInfoText,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Trim Video Section
            Row(
                modifier = Modifier
                    .clickable { isTrimExpanded = !isTrimExpanded }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trim Video", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTrimExpanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            AnimatedVisibility(visible = isTrimExpanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CustomTrimTextField(
                            value = trimStartTime,
                            onValueChange = { trimStartTime = it },
                            label = "Start time:"
                        )
                        CustomTrimTextField(
                            value = trimEndTime,
                            onValueChange = { trimEndTime = it },
                            label = "End time:"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { outputLauncher.launch(null) }) {
                Text("Choose Output Directory")
            }
            Text(
                text = outputFileName?.let { "Dir: $it" } ?: "No output selected",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = userTypedFilename,
                onValueChange = { userTypedFilename = it },
                label = { Text("Output Filename") },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = {
                    val currentInputUri = inputUri
                    val currentOutputUri = outputUri
                    if (currentInputUri != null && currentOutputUri != null) {
                        isProcessing = true
                        currentProgress = 0f
                        statusText = "Processing..."
                        val inputSafUrl = FFmpegKitConfig.getSafParameterForRead(context, currentInputUri)
                        
                        val dir = DocumentFile.fromTreeUri(context, currentOutputUri)
                        val newFile = dir?.createFile("video/mp4", userTypedFilename)
                        if (newFile != null) {
                            val outputSafUrl = FFmpegKitConfig.getSafParameterForWrite(context, newFile.uri)
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
                        } else {
                            isProcessing = false
                            statusText = "Failed to create output file"
                        }
                    }
                },
                enabled = inputUri != null && outputUri != null && userTypedFilename.isNotBlank() && !isProcessing
            ) {
                Text("Run FFmpeg")
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            if (isProcessing) {
                LinearProgressIndicator(
                    progress = { currentProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Processing: ${(currentProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "Status: $statusText",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

fun buildFfmpegCommand(startTime: String, endTime: String, inputUrl: String, outputUrl: String): String {
    val ssPart = if (startTime.isNotBlank()) "-ss $startTime " else ""
    val toPart = if (endTime.isNotBlank()) "-to $endTime " else ""
    return "$ssPart$toPart-i $inputUrl -c:v libx264 -y $outputUrl"
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
