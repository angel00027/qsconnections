package com.example.qsconnection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qsconnection.ui.theme.*

@Composable
fun PayloadSenderScreen(vm: MainViewModel, onOpenConnections: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val profiles by vm.profiles.collectAsState()

    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            fileUri = uri
            fileName = getFileName(context, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 🔌 SECCIÓN DE CONEXIÓN
        SectionCard(title = "Conexión (perfil y puertos)", icon = Icons.Default.Computer) {
            val active = profiles.firstOrNull { it.ip == vm.ip && it.port == vm.port }
            Surface(
                color = PS_DarkBlue,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Storage, null, modifier = Modifier.size(20.dp), tint = PS_Triangle)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = active?.name ?: "Sin perfil",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = PS_White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${vm.ip.ifBlank { "IP sin configurar" }} : ${vm.port.ifBlank { "9020" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PS_LightBlue
                        )
                    }
                    TextButton(onClick = onOpenConnections) {
                        Text("Gestionar", color = PS_Triangle)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "El payload se envía por TCP al puerto indicado. Debe coincidir con el de tu consola: " +
                    "PS4/PS5 suele ser 9020 (o 9090) y PS3 9020. Cambia el puerto en Perfil si usas otro.",
                style = MaterialTheme.typography.bodySmall,
                color = PS_White.copy(alpha = 0.85f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // 📂 SECCIÓN DE PAYLOAD
        SectionCard(title = "Payload (.bin)", icon = Icons.Default.FilePresent) {
            Button(
                onClick = { launcher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
            ) {
                Icon(Icons.Default.FileUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("Cargar archivo (.bin)")
            }

            if (fileName != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = PS_DarkBlue,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, PS_Blue)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(20.dp), tint = PS_Triangle)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = fileName!!,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            color = PS_White
                        )
                        IconButton(onClick = { fileUri = null; fileName = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = PS_Circle)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 🚀 ACCIONES
        Row(modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { vm.testConnection() },
                modifier = Modifier.weight(1f),
                enabled = !vm.isTesting && !vm.isSending,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PS_Blue.copy(alpha = 0.2f),
                    contentColor = PS_LightBlue
                )
            ) {
                if (vm.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PS_LightBlue)
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Probar")
            }

            Spacer(Modifier.width(12.dp))

            Button(
                onClick = {
                    fileUri?.let { uri -> vm.sendPayload(uri, context, fileName) }
                },
                modifier = Modifier.weight(1f),
                enabled = !vm.isSending && fileUri != null,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
            ) {
                if (vm.isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PS_White)
                } else {
                    Icon(Icons.Default.Send, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Enviar")
            }
        }

        Spacer(Modifier.height(20.dp))

        // 📊 ESTADO Y PROGRESO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = PS_DarkBlue.copy(alpha = 0.5f)
            ),
            border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Estado: ${vm.status}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PS_White
                    )
                    if (vm.isSending) {
                        Text(
                            text = if (vm.installIndeterminate) "..." else "${(vm.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = PS_Triangle
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (vm.installIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = PS_Triangle,
                        trackColor = PS_Blue.copy(alpha = 0.2f)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { vm.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = PS_Triangle,
                        trackColor = PS_Blue.copy(alpha = 0.2f)
                    )
                }

                if (vm.isSending) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp), tint = PS_LightBlue)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = vm.transferSpeed,
                                style = MaterialTheme.typography.labelMedium,
                                color = PS_LightBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (vm.transferredSize.isNotBlank()) {
                            Text(
                                text = vm.transferredSize,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.cancelUpload() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PS_Circle),
                        border = BorderStroke(1.dp, PS_Circle)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancelar envío")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 📜 LOGS
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Registro de Actividad",
                style = MaterialTheme.typography.labelLarge,
                color = PS_LightBlue
            )
            IconButton(onClick = { vm.clearLogs() }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.DeleteSweep, "Limpiar", tint = PS_Circle, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = {
                    if (vm.logs.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(vm.logs.joinToString("\n")))
                    }
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.ContentCopy, "Copiar registro", tint = PS_LightBlue, modifier = Modifier.size(18.dp))
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PS_DarkBlue.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.2f))
        ) {
            LazyColumn(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize()
            ) {
                items(vm.logs) { log ->
                    LogEntry(log)
                }
            }
        }
    }
}