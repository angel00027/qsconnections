package com.example.qsconnection

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qsconnection.ui.theme.*

@Composable
fun InstallerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsState()

    val pkgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.pendingPkgUri = uri
            vm.pendingPkgName = getFileName(context, uri)
            vm.pendingPkgSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
    }

    val savePkgPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.saveEssentialPkg(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Instalar PKG",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PS_White
            )
            if (vm.isInstalling) {
                Text(
                    text = vm.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = PS_Triangle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Perfil y conexión", icon = Icons.Default.Computer) {
            val active = profiles.firstOrNull { it.ip == vm.ip && it.port == vm.port }
            var showProfiles by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = PS_DarkBlue,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = profiles.isNotEmpty()) { showProfiles = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Storage, null, modifier = Modifier.size(20.dp), tint = PS_Triangle)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = active?.name ?: "Seleccionar perfil",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = PS_White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (vm.ip.isBlank()) "Sin perfil seleccionado"
                                else "${vm.ip}  ·  ${vm.port.ifBlank { "9020" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = PS_LightBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, null, tint = PS_LightBlue)
                    }
                }
                DropdownMenu(
                    expanded = showProfiles,
                    onDismissRequest = { showProfiles = false },
                    containerColor = PS_DarkBlue
                ) {
                    if (profiles.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No hay perfiles guardados", color = Color.Gray) },
                            onClick = { showProfiles = false }
                        )
                    } else {
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.name}  (${p.ip}:${p.port})", color = PS_White) },
                                onClick = {
                                    vm.selectProfile(p)
                                    showProfiles = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = { vm.testPkgConnection() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.isTesting && !vm.isSending && vm.ip.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PS_Triangle.copy(alpha = 0.15f),
                    contentColor = PS_Triangle
                )
            ) {
                if (vm.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PS_Triangle)
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Probar conexión")
            }

            vm.lastInstallTest?.let { result ->
                Spacer(Modifier.height(12.dp))
                InstallTestBanner(result = result, onDismiss = { vm.lastInstallTest = null })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "PKG esencial (instalar una vez)", icon = Icons.Default.Extension) {
            Text(
                text = "Este paquete (${MainViewModel.ESSENTIAL_PKG_NAME}) va incluido en la app " +
                    "y es necesario para habilitar las instalaciones en la consola. " +
                    "Guárdalo en el teléfono, súbelo por FTP y instálalo una sola vez.",
                style = MaterialTheme.typography.bodySmall,
                color = PS_White.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.saveEssentialPkg(context)
                    } else {
                        savePkgPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PS_Blue.copy(alpha = 0.2f),
                    contentColor = PS_LightBlue
                )
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Guardar en Descargas")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Paquete", icon = Icons.Default.FileDownload) {
            Button(
                onClick = { pkgLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !vm.isInstalling && vm.ip.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
            ) {
                Icon(Icons.Default.FileUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.ip.isBlank()) "Primero indica la IP de la PS4" else "Seleccionar archivo .pkg")
            }

            if (vm.pendingPkgUri != null && vm.pendingPkgName != null) {
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
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(20.dp), tint = PS_Triangle)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vm.pendingPkgName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = PS_White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = vm.formatSize(vm.pendingPkgSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        IconButton(
                            onClick = { vm.pendingPkgUri = null; vm.pendingPkgName = null; vm.pendingPkgSize = 0L },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = PS_Circle)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    vm.pendingPkgUri?.let {
                        vm.installPkg(it, vm.pendingPkgName ?: "paquete.pkg")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = vm.pendingPkgUri != null && vm.ip.isNotBlank() && !vm.isSending && !vm.isInstalling,
                colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
            ) {
                if (vm.isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.SystemUpdate, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (vm.isInstalling) "Instalando..." else "Instalar en la PS4")
            }

            if (vm.installFailed && !vm.isInstalling) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = PS_Circle.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PS_Circle.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(20.dp), tint = PS_Circle)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "La instalación falló",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = PS_Circle
                            )
                        }
                        if (vm.installError.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                vm.installError,
                                style = MaterialTheme.typography.bodySmall,
                                color = PS_White.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = {
                                vm.pendingPkgUri?.let {
                                    vm.installPkg(it, vm.pendingPkgName ?: "paquete.pkg")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = vm.pendingPkgUri != null && vm.ip.isNotBlank() && !vm.isSending && !vm.isInstalling,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = PS_Triangle.copy(alpha = 0.15f),
                                contentColor = PS_Triangle
                            )
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reintentar / Reanudar")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Progreso de instalación", icon = Icons.Default.Speed) {
            if (!vm.isInstalling && vm.progress <= 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.HourglassEmpty, null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sin instalación activa", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                if (vm.isInstalling && vm.installUrl.isNotBlank()) {
                    Surface(
                        color = PS_DarkBlue.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp), tint = PS_LightBlue)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = vm.installUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = PS_LightBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    text = vm.status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = PS_White
                )

                Spacer(Modifier.height(8.dp))

                if (vm.installIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PS_Triangle
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { vm.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = PS_Triangle
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (vm.installIndeterminate) "..." else "${(vm.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = PS_White
                    )
                    Text(
                        text = vm.transferSpeed,
                        style = MaterialTheme.typography.labelMedium,
                        color = PS_LightBlue
                    )
                    if (!vm.installIndeterminate && vm.remainingTime.isNotBlank() && vm.remainingTime != "--:--") {
                        Text(
                            text = vm.remainingTime,
                            style = MaterialTheme.typography.labelMedium,
                            color = PS_White
                        )
                    }
                }

                if (vm.transferredSize.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = vm.transferredSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }

                if (vm.isInstalling) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.cancelInstall() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PS_Circle)
                    ) {
                        Icon(Icons.Default.Close, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancelar instalación")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Cómo funciona", icon = Icons.Default.Info) {
            StepItem("1", "La PS4 y el teléfono deben estar en la misma red WiFi.")
            StepItem("2", "Selecciona un perfil y pulsa 'Probar conexión'.")
            StepItem("3", "El teléfono se convierte en servidor: la PS4 descargará el .pkg desde el puerto 9898. Ese puerto no se abre en la PS4, lo escucha el móvil.")
            StepItem("4", "La barra refleja los bytes servidos; al terminar, la PS4 instala el paquete.")
        }
    }
}

@Composable
fun InstallTestBanner(result: InstallTestResult, onDismiss: () -> Unit) {
    val accent = if (result.ok) PS_Triangle else PS_Circle
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (result.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = accent
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (result.ok) "Conexión exitosa" else "Error de conexión",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    if (result.port.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = accent.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "puerto ${result.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = PS_White.copy(alpha = 0.85f)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = PS_White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun StepItem(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = PS_Blue,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(18.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    color = PS_White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PS_White.copy(alpha = 0.85f)
        )
    }
}