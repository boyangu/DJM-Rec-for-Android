package com.audiopro.djmrec.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.diagnostics.LogExporter
import com.audiopro.djmrec.usb.UsbAudioDescriptorParser
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val device by (context.applicationContext as DjmRecApplication).usbAudioManager.deviceState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Support & diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create a technical report when USB capture or file encoding behaves unexpectedly.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text("Diagnostic report", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Includes app logs, USB descriptors, capture statistics, and current settings. Audio files are never included.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val report = LogExporter.collectDiagnosticReport(context)
                                    LogExporter.writeReportToFile(context, report)
                                }
                                LogExporter.shareReport(context, file)
                            } catch (error: Exception) {
                                Toast.makeText(
                                    context,
                                    "Failed to export logs: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                exporting = false
                            }
                        }
                    }
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create and share report")
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Icon(Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("USB descriptors", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    device?.let {
                        "${it.productName} (${hex4(it.vendorId)}:${hex4(it.productId)}) - ${it.profileDescription}. " +
                            "Copy the raw configuration descriptors to finish or verify a mixer profile " +
                            "(needed for the DJM-V5 and any model marked unverified)."
                    } ?: "Connect a mixer and grant USB access to read its descriptors.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    enabled = device?.rawDescriptors?.isNotEmpty() == true,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        val info = device ?: return@OutlinedButton
                        val text = describeDescriptors(info)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Set Recorder USB descriptors", text))
                        Toast.makeText(context, "USB descriptors copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Copy USB descriptors") }
            }
        }
    }
}

private fun hex4(value: Int) = "0x" + value.and(0xFFFF).toString(16).padStart(4, '0')

/** Human-readable + hex dump of the published device's descriptors (no serial numbers involved). */
private fun describeDescriptors(info: UsbAudioDeviceInfo): String = buildString {
    appendLine("Set Recorder USB descriptor dump")
    appendLine("product: ${info.productName}")
    appendLine("usb id: ${hex4(info.vendorId)}:${hex4(info.productId)}")
    appendLine("profile: ${info.profileDescription}")
    appendLine(
        "selected capture: if${info.streamingInterfaceNumber}/alt${info.activeAlternateSetting} " +
            "ep=0x${info.isochronousInEndpointAddress.toString(16)} maxPacket=${info.isochronousInMaxPacketSize} " +
            "${info.channelCount}ch/${info.bitResolution}bit/subframe${info.subframeSize} " +
            "rates=${info.supportedSampleRates} formatGuessed=${info.formatGuessed}"
    )
    val streaming = runCatching { UsbAudioDescriptorParser.findAudioStreamingInterfaces(info.rawDescriptors) }.getOrDefault(emptyList())
    appendLine("UAC AudioStreaming alternates: ${streaming.size}")
    streaming.forEach {
        appendLine(
            "  if${it.interfaceNumber}/alt${it.alternateSetting} ${it.channelCount}ch/${it.bitResolution}bit/" +
                "subframe${it.subframeSize} inEp=${it.isochronousInEndpointAddress?.toString(16) ?: "none"} " +
                "maxPacket=${it.isochronousInMaxPacketSize} rates=${it.sampleRates}"
        )
    }
    val anyIso = runCatching { UsbAudioDescriptorParser.findAnyIsoInEndpoints(info.rawDescriptors) }.getOrDefault(emptyList())
    appendLine("all isochronous IN endpoints (any interface class): ${anyIso.size}")
    anyIso.forEach {
        appendLine(
            "  if${it.interfaceNumber}/alt${it.alternateSetting} class=${it.interfaceClass} " +
                "ep=0x${it.isochronousInEndpointAddress?.toString(16)} maxPacket=${it.isochronousInMaxPacketSize}"
        )
    }
    appendLine("raw descriptors (${info.rawDescriptors.size} bytes):")
    info.rawDescriptors.toList().chunked(16).forEachIndexed { line, bytes ->
        append((line * 16).toString(16).padStart(4, '0')).append(": ")
        appendLine(bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }
}
