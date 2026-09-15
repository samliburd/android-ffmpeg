package com.example.ffmpegapp

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var statusText by remember { mutableStateOf("Idle") }
    var isProcessing by remember { mutableStateOf(false) }
    var mediaInfoText by remember { mutableStateOf("") }
    var isMediaInfoExpanded by remember { mutableStateOf(false) }

    val inputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        inputUri = uri
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                mediaInfoText = "Extracting media info..."
                val inputSafUrl = FFmpegKitConfig.getSafParameterForRead(context, uri)
                val session = FFprobeKit.getMediaInformation(inputSafUrl)
                val info = session.getMediaInformation()
                if (info != null) {
                    val text = StringBuilder()
                    text.appendLine("Duration: ${info.getDuration() ?: "N/A"} s")
                    text.appendLine("Format: ${info.getFormat() ?: "N/A"}")
                    text.appendLine("Bitrate: ${info.getBitrate() ?: "N/A"} bps")
                    
                    val streams = info.getStreams()
                    streams?.forEach { stream ->
                        if (stream.getType() == "video") {
                            text.appendLine("Video Codec: ${stream.getCodec() ?: "N/A"}")
                            text.appendLine("Resolution: ${stream.getWidth() ?: "?"}x${stream.getHeight() ?: "?"}")
                            stream.getStringProperty("color_space")?.let { colorSpace ->
                                text.appendLine("Color Space: $colorSpace")
                            }
                        } else if (stream.getType() == "audio") {
                            text.appendLine("Audio Codec: ${stream.getCodec() ?: "N/A"}")
                        }
                    }
                    mediaInfoText = text.toString()
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
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        outputUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = { inputLauncher.launch("video/*") }) {
            Text("Select Input Video")
        }
        Text(
            text = inputUri?.toString() ?: "No input selected",
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

        Button(onClick = { outputLauncher.launch("output.mp4") }) {
            Text("Choose Output Location")
        }
        Text(
            text = outputUri?.toString() ?: "No output selected",
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                val currentInputUri = inputUri
                val currentOutputUri = outputUri
                if (currentInputUri != null && currentOutputUri != null) {
                    isProcessing = true
                    statusText = "Processing..."
                    val inputSafUrl = FFmpegKitConfig.getSafParameterForRead(context, currentInputUri)
                    val outputSafUrl = FFmpegKitConfig.getSafParameterForWrite(context, currentOutputUri)
                    // Removed the scaling logic (-vf scale=340:-1) per requirements
                    val command = "-i $inputSafUrl -c:v libx264 -an -y $outputSafUrl"

                    FFmpegKit.executeAsync(command) { session ->
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
                    }
                }
            },
            enabled = inputUri != null && outputUri != null && !isProcessing
        ) {
            Text("Run FFmpeg")
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Status: $statusText",
            style = MaterialTheme.typography.titleMedium
        )
        
        if (isProcessing) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}
