package com.example.qsconnection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qsconnection.ui.theme.*
import org.apache.commons.net.ftp.FTPFile

@Composable
fun FtpScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsState()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "upload.bin"
            vm.enqueueUpload(it, context, fileName)
        }
    }

    var pendingDownloadName by remember { mutableStateOf<String?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { destUri ->
            pendingDownloadName?.let { fileName ->
                vm.enqueueDownload(fileName, context, destUri)
            }
        }
        pendingDownloadName = null
    }

    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var newFileName by remember { mutableStateOf("") }

    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Renombrar", color = PS_White) },
            text = {
                TextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    singleLine = true,
                    label = { Text("Nuevo nombre") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PS_DarkBlue,
                        unfocusedContainerColor = PS_DarkBlue,
                        focusedTextColor = PS_White,
                        unfocusedTextColor = PS_White
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog?.let { old ->
                        if (newFileName.isNotBlank()) vm.renameFTPFile(old, newFileName)
                    }
                    showRenameDialog = null
                    newFileName = ""
                }) { Text("Aceptar", color = PS_Triangle) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancelar", color = PS_Circle) }
            },
            containerColor = PS_DarkBlue
        )
    }

    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var newProfileMac by remember { mutableStateOf("") }

    if (showSaveProfileDialog) {
        AlertDialog(
            onDismissRequest = { showSaveProfileDialog = false },
            containerColor = PS_DarkBlue,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Save, null, tint = PS_LightBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("Guardar Perfil", color = PS_White)
                }
            },
            text = {
                Column {
                    Text(
                        "Se guardará ${vm.ip}:${vm.port}" +
                            if (vm.anonymousAuth) " sin usuario/contraseña."
                            else " con el usuario ${vm.ftpUsername.trim().ifEmpty { "—" }}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PS_White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Nombre del perfil") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PS_LightBlue,
                            unfocusedBorderColor = PS_Blue,
                            focusedTextColor = PS_White,
                            unfocusedTextColor = PS_White,
                            focusedLabelColor = PS_LightBlue,
                            unfocusedLabelColor = PS_Blue
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newProfileMac,
                        onValueChange = { newProfileMac = it },
                        label = { Text("MAC Address (opcional, WoL)") },
                        placeholder = { Text("00:11:22:33:44:55") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PS_LightBlue,
                            unfocusedBorderColor = PS_Blue,
                            focusedTextColor = PS_White,
                            unfocusedTextColor = PS_White,
                            focusedLabelColor = PS_LightBlue,
                            unfocusedLabelColor = PS_Blue
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            vm.saveProfile(
                                name = newProfileName,
                                mac = newProfileMac.ifBlank { null },
                                profileIp = vm.ip,
                                profilePort = vm.port,
                                username = vm.ftpUsername,
                                password = vm.ftpPassword,
                                anonymous = vm.anonymousAuth
                            )
                            showSaveProfileDialog = false
                            newProfileName = ""
                            newProfileMac = ""
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                ) { Text("Guardar", color = PS_White) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveProfileDialog = false
                    newProfileName = ""
                    newProfileMac = ""
                }) { Text("Cancelar", color = PS_Circle) }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = PS_DarkBlue.copy(alpha = 0.95f)
                    ),
                    border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (profiles.isNotEmpty() && !vm.isFtpConnected) {
                            Text(
                                "Seleccionar Perfil:",
                                style = MaterialTheme.typography.labelMedium,
                                color = PS_LightBlue
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(profiles) { profile ->
                                    val selected = vm.ip == profile.ip && vm.port == profile.port
                                    Surface(
                                        onClick = { vm.selectProfile(profile) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) PS_Blue.copy(alpha = 0.3f) else PS_DarkBlue.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, if (selected) PS_LightBlue else PS_Blue.copy(alpha = 0.3f)),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Memory, null, modifier = Modifier.size(14.dp), tint = PS_Triangle)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${profile.name} (${profile.port})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PS_White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = vm.ip,
                                onValueChange = { vm.ip = it },
                                label = { Text("IP de la Consola", color = PS_LightBlue) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Computer, null, modifier = Modifier.size(20.dp), tint = PS_LightBlue) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PS_LightBlue,
                                    unfocusedBorderColor = PS_Blue,
                                    focusedTextColor = PS_White,
                                    unfocusedTextColor = PS_White
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = vm.port,
                                onValueChange = { vm.port = it },
                                label = { Text("Port", color = PS_LightBlue) },
                                modifier = Modifier.width(90.dp),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PS_LightBlue,
                                    unfocusedBorderColor = PS_Blue,
                                    focusedTextColor = PS_White,
                                    unfocusedTextColor = PS_White
                                )
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Compatible con todas las consolas: elige un preset o escribe el puerto.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ConsolePreset("PS3 · 21") { vm.port = "21" }
                            ConsolePreset("PS4/PS5 · 2121") { vm.port = "2121" }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = vm.anonymousAuth,
                                    onCheckedChange = { vm.anonymousAuth = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = PS_Triangle,
                                        uncheckedColor = PS_LightBlue
                                    )
                                )
                                Text(
                                    "Sin usuario/contraseña",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PS_White
                                )
                            }
                            TextButton(
                                onClick = { showSaveProfileDialog = true },
                                enabled = vm.ip.isNotBlank() && !vm.isFtpLoading
                            ) {
                                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp), tint = PS_Triangle)
                                Spacer(Modifier.width(4.dp))
                                Text("Guardar perfil", style = MaterialTheme.typography.labelMedium, color = PS_Triangle)
                            }
                        }

                        if (!vm.anonymousAuth) {
                            Spacer(Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = vm.ftpUsername,
                                    onValueChange = { vm.ftpUsername = it },
                                    label = { Text("Usuario", color = PS_LightBlue) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PS_LightBlue,
                                        unfocusedBorderColor = PS_Blue,
                                        focusedTextColor = PS_White,
                                        unfocusedTextColor = PS_White
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = vm.ftpPassword,
                                    onValueChange = { vm.ftpPassword = it },
                                    label = { Text("Contraseña", color = PS_LightBlue) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PS_LightBlue,
                                        unfocusedBorderColor = PS_Blue,
                                        focusedTextColor = PS_White,
                                        unfocusedTextColor = PS_White
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "Estado: ${vm.status}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vm.status.contains("Error") || vm.status.contains("Sin")) PS_Circle else PS_Triangle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(12.dp))

                        if (vm.isFtpConnected) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = PS_Triangle, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = vm.currentPath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = PS_White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { vm.disconnectFTP() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, "Desconectar", tint = PS_Circle)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        if (!vm.isFtpConnected) {
                            Button(
                                onClick = { vm.connectFTP() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !vm.isFtpLoading,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
                            ) {
                                if (vm.isFtpLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PS_White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Link, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Conectar FTP")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { vm.connectFTP() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !vm.isFtpLoading,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                                ) {
                                    Icon(Icons.Default.Refresh, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Refrescar")
                                }
                                Button(
                                    onClick = { launcher.launch("*/*") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
                                ) {
                                    Icon(Icons.Default.FileUpload, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Subir")
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (vm.isFtpConnected) {
                FloatingActionButton(
                    onClick = { launcher.launch("*/*") },
                    containerColor = PS_Triangle,
                    contentColor = PS_White,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Subir aquí")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (vm.isFtpLoading && vm.ftpFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PS_Triangle)
                    }
                } else if (!vm.isFtpConnected && !vm.isFtpLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(64.dp), tint = PS_Blue.copy(alpha = 0.5f))
                            Spacer(Modifier.height(12.dp))
                            Text("Conéctate para explorar", color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    }
                } else if (vm.ftpFiles.isEmpty() && vm.isFtpConnected && !vm.isFtpLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Carpeta vacía", color = Color.Gray)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (vm.currentPath != "/" && vm.isFtpConnected) {
                        item {
                            FileRow(
                                name = "..",
                                isDirectory = true,
                                vm = vm,
                                onDelete = {},
                                onRename = {}
                            ) {
                                vm.cdFTP("..")
                            }
                        }
                    }

                    items(vm.ftpFiles) { file ->
                        FileRow(
                            name = file.name,
                            isDirectory = file.isDirectory,
                            size = file.size,
                            vm = vm,
                            onDelete = { vm.deleteFTPFile(file.name, file.isDirectory) },
                            onRename = {
                                newFileName = file.name
                                showRenameDialog = file.name
                            },
                            onDownload = {
                                pendingDownloadName = file.name
                                downloadLauncher.launch(file.name)
                            }
                        ) {
                            if (file.isDirectory) {
                                vm.cdFTP(file.name)
                            }
                        }
                    }
                }
            }

            if (vm.showUploadOverlay && vm.isSending) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PS_Black.copy(alpha = 0.85f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = PS_DarkBlue),
                            border = BorderStroke(1.dp, PS_LightBlue.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    IconButton(
                                        onClick = { vm.showUploadOverlay = false },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Close, "Ocultar", tint = Color.Gray)
                                    }
                                }

                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = PS_Triangle
                                )
                                
                                Spacer(Modifier.height(16.dp))
                                
                                Text(
                                    text = vm.status,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PS_White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                
                                Spacer(Modifier.height(24.dp))
                                
                                LinearProgressIndicator(
                                    progress = { vm.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp)),
                                    color = PS_Triangle,
                                    trackColor = PS_Blue.copy(alpha = 0.3f)
                                )
                                
                                Spacer(Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${(vm.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = PS_White
                                    )
                                    Text(
                                        text = vm.transferredSize,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.Gray
                                    )
                                }
                                
                                Spacer(Modifier.height(24.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    InfoItem(
                                        icon = Icons.Default.Speed,
                                        label = "Velocidad",
                                        value = vm.transferSpeed
                                    )
                                    InfoItem(
                                        icon = Icons.Default.Timer,
                                        label = "Restante",
                                        value = vm.remainingTime
                                    )
                                }

                                Spacer(Modifier.height(32.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (vm.status.contains("Error") || vm.status.contains("Fallo")) {
                                        Button(
                                            onClick = { vm.retryUpload(context) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                                        ) {
                                            Icon(Icons.Default.Refresh, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Reintentar")
                                        }
                                    }

                                    Button(
                                        onClick = { vm.cancelUpload() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PS_Circle),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Stop, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (vm.status.contains("Error")) "Cerrar" else "Cancelar")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConsolePreset(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = PS_Blue.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.5f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = PS_LightBlue
        )
    }
}

@Composable
fun InfoItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = PS_LightBlue)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = PS_White)
    }
}

@Composable
fun FileRow(
    name: String,
    isDirectory: Boolean,
    size: Long = 0,
    vm: MainViewModel,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit = {},
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isDirectory) PS_Triangle.copy(alpha = 0.1f) else PS_LightBlue.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = if (isDirectory) PS_Triangle else PS_LightBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name, 
                    style = MaterialTheme.typography.bodyLarge, 
                    fontWeight = FontWeight.Medium, 
                    color = PS_White,
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                if (!isDirectory && name != "..") {
                    Text(
                        vm.formatSize(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
            
            if (name != "..") {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.Gray)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = PS_DarkBlue
                    ) {
                        if (!isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Descargar", color = PS_White) },
                                onClick = {
                                    showMenu = false
                                    onDownload()
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null, tint = PS_Triangle) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Renombrar", color = PS_White) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = PS_LightBlue) }
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar", color = PS_Circle) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = PS_Circle) }
                        )
                    }
                }
            } else if (isDirectory) {
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray.copy(alpha = 0.5f))
            }
        }
    }
}
