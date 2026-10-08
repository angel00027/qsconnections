package com.example.qsconnection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.qsconnection.ui.theme.*

@Composable
fun TransfersScreen(vm: MainViewModel) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transferencias",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PS_White
            )
            if (vm.activeTransfers.isNotEmpty()) {
                TextButton(onClick = { vm.clearCompletedTasks() }) {
                    Text("Limpiar finalizadas", color = PS_Triangle)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (vm.isInstalling || vm.serverRunning || vm.isSending) {
            CurrentOperationCard(vm)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (vm.activeTransfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(64.dp), tint = PS_LightBlue.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text("No hay transferencias activas", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(vm.activeTransfers.size) { index ->
                    val task = vm.activeTransfers[index]
                    TransferCard(
                        task = task,
                        onPauseToggle = { vm.togglePauseTask(task) },
                        onCancel = { vm.cancelTask(task) },
                        onResume = { vm.resumeTask(task, context) },
                        onRemove = { vm.removeTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
fun CurrentOperationCard(vm: MainViewModel) {
    val installing = vm.isInstalling
    val serving = vm.serverRunning

    val title: String
    val subtitle: String
    val onCancel: () -> Unit
    when {
        installing -> {
            title = "Instalando PKG"
            subtitle = vm.installFileName.ifBlank { vm.status }
            onCancel = { vm.cancelInstall() }
        }
        serving -> {
            title = "Servidor local activo"
            subtitle = vm.serverFileName.ifBlank { vm.serverUrl }
            onCancel = { vm.stopServer() }
        }
        else -> {
            title = "Enviando a la consola"
            subtitle = vm.status
            onCancel = { vm.cancelUpload() }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PS_Triangle.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = PS_DarkBlue.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, null, tint = PS_Triangle, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = PS_White)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = PS_LightBlue,
                        maxLines = 1
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("Detener", color = PS_Circle)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (installing && vm.installIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = PS_Triangle,
                    trackColor = PS_Blue.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    progress = { vm.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = PS_Triangle,
                    trackColor = PS_Blue.copy(alpha = 0.2f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${(vm.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = PS_White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp), tint = PS_LightBlue)
                    Spacer(Modifier.width(4.dp))
                    Text(vm.transferSpeed, style = MaterialTheme.typography.labelMedium, color = PS_LightBlue, fontWeight = FontWeight.Bold)
                }
                if (vm.transferredSize.isNotBlank()) {
                    Text(vm.transferredSize, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun TransferCard(
    task: MainViewModel.TransferTask,
    onPauseToggle: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    val statusColor = when {
        task.status.contains("Error") -> PS_Circle
        task.status.contains("Completado") -> PS_Triangle
        task.status.contains("Cancelado") -> Color.Gray
        task.status.contains("Reconectando") -> PS_Square
        task.status == "Pausado" -> PS_Square
        else -> PS_LightBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(PS_Blue, PS_LightBlue))),
        colors = CardDefaults.cardColors(
            containerColor = PS_DarkBlue.copy(alpha = 0.8f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PS_Blue.copy(alpha = 0.3f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (task.isDownload) Icons.Default.Download else Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = PS_White,
                        maxLines = 1
                    )
                    Text(
                        text = task.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 2
                    )
                }

                if (task.isRunning) {
                    Row {
                        IconButton(onClick = onPauseToggle) {
                            Icon(
                                if (task.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                if (task.isPaused) "Reanudar" else "Pausar",
                                tint = PS_Triangle
                            )
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "Cancelar", tint = PS_Circle)
                        }
                    }
                } else {
                    Row {
                        if (task.isResumable) {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Default.PlayArrow, "Reanudar", tint = PS_Triangle)
                            }
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.DeleteOutline, "Quitar", tint = Color.Gray)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = statusColor,
                trackColor = PS_Blue.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${(task.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = PS_White)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp), tint = PS_LightBlue)
                    Spacer(Modifier.width(4.dp))
                    Text(task.speed, style = MaterialTheme.typography.labelMedium, color = PS_LightBlue, fontWeight = FontWeight.Bold)
                }

                if (task.remainingTime.isNotBlank() && task.remainingTime != "--:--") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                        Spacer(Modifier.width(4.dp))
                        Text(task.remainingTime, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    }
                }

                Text(task.sizeInfo, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            }

            if (!task.isRunning && task.bytesProcessed > 0 && task.isResumable) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(14.dp), tint = PS_Square)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Continuar desde el byte ${task.bytesProcessed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PS_Square
                    )
                }
            }
        }
    }
}
