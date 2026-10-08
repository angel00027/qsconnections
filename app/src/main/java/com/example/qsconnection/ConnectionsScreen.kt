package com.example.qsconnection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qsconnection.ui.theme.*

@Composable
fun ConnectionsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var customPort by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Conexiones",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PS_White
        )
        Text(
            text = "Perfiles, escaneo de red y pruebas de puertos.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Perfil activo", icon = Icons.Default.Computer) {
            val active = profiles.firstOrNull { it.ip == vm.ip && it.port == vm.port }
            Surface(
                color = PS_DarkBlue,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PS_Blue),
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
                            text = active?.name ?: "Sin perfil seleccionado",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = PS_White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${vm.ip.ifBlank { "192.168.1.XX" }} : ${vm.port.ifBlank { "9020" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PS_LightBlue
                        )
                    }
                    if (vm.ip.isNotBlank()) {
                        TextButton(onClick = { showSaveDialog = true }) {
                            Text("Guardar", color = PS_Triangle)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Perfiles guardados", icon = Icons.Default.Save) {
            if (profiles.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text("Aún no hay perfiles. Guarda uno para reutilizarlo.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.sortedByDescending { it.lastUsed }.forEach { profile ->
                        val isSelected = vm.ip == profile.ip && vm.port == profile.port
                        Surface(
                            color = if (isSelected) PS_Blue.copy(alpha = 0.3f) else PS_DarkBlue,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) PS_LightBlue else PS_Blue),
                            modifier = Modifier.fillMaxWidth().clickable { vm.selectProfile(profile) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Memory, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) PS_Triangle else PS_LightBlue
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = PS_White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${profile.ip}:${profile.port}" +
                                            (if (profile.mac.isNullOrBlank()) "" else " • MAC: ${profile.mac}") +
                                            (if (profile.anonymous) "" else if (profile.username.isNullOrBlank()) "" else " • ${profile.username}"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp), tint = PS_Triangle)
                                }
                                IconButton(onClick = { vm.deleteProfile(profile) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = PS_Circle)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Escanear red", icon = Icons.Default.Radar) {
            Button(
                onClick = { vm.scanNetwork(context) },
                enabled = !vm.isScanning && !vm.isSending,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
            ) {
                if (vm.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PS_White)
                    Spacer(Modifier.width(8.dp))
                    Text("Escaneando...")
                } else {
                    Icon(Icons.Default.Radar, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escanear la red local")
                }
            }

            if (vm.scanResults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dispositivos encontrados (toca para seleccionar):",
                    style = MaterialTheme.typography.labelMedium,
                    color = PS_LightBlue
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.scanResults.forEach { result ->
                        val isSelected = vm.ip == result.ip
                        Surface(
                            color = if (isSelected) PS_Blue.copy(alpha = 0.3f) else PS_DarkBlue,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isSelected) PS_LightBlue else PS_Blue),
                            modifier = Modifier.fillMaxWidth().clickable { vm.selectScanResult(result) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Computer, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) PS_Triangle else PS_LightBlue
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.ip,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = PS_White
                                    )
                                    Text(
                                        text = "puertos: ${result.openPorts.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp), tint = PS_Triangle)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(title = "Probar conexión", icon = Icons.Default.NetworkCheck) {
            Text(
                text = "Destino: ${vm.ip.ifBlank { "sin IP" }}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = PS_White
            )
            Spacer(Modifier.height(8.dp))

            val ports = listOf(
                9020 to "9020 Payload",
                9090 to "9090 Payload",
                12800 to "12800 Instalar",
                21 to "21 FTP",
                2121 to "2121 FTP",
                3232 to "3232"
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ports) { (port, label) ->
                    OutlinedButton(
                        onClick = { vm.testPort(port) },
                        enabled = !vm.isTesting && !vm.isSending && vm.ip.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PS_Blue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PS_LightBlue)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customPort,
                    onValueChange = { newValue ->
                        if (newValue.length <= 5 && newValue.all { it.isDigit() }) {
                            customPort = newValue
                        }
                    },
                    label = { Text("Puerto personalizado") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PS_LightBlue,
                        unfocusedBorderColor = PS_Blue,
                        focusedTextColor = PS_White,
                        unfocusedTextColor = PS_White,
                        focusedLabelColor = PS_LightBlue,
                        unfocusedLabelColor = PS_Blue
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { customPort.toIntOrNull()?.let { vm.testPort(it) } },
                    enabled = !vm.isTesting && !vm.isSending && vm.ip.isNotBlank() && customPort.toIntOrNull() != null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                ) {
                    if (vm.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.PlayArrow, null)
                    }
                }
            }

            vm.lastInstallTest?.let { result ->
                Spacer(Modifier.height(12.dp))
                InstallTestBanner(result = result, onDismiss = { vm.lastInstallTest = null })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showSaveDialog) {
        SaveProfileDialog(vm) { showSaveDialog = false }
    }
}

@Composable
fun SaveProfileDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var profileName by remember { mutableStateOf("") }
    var profileIp by remember { mutableStateOf(vm.ip) }
    var profilePort by remember { mutableStateOf(vm.port) }
    var username by remember { mutableStateOf(vm.ftpUsername) }
    var password by remember { mutableStateOf(vm.ftpPassword) }
    var anonymous by remember { mutableStateOf(vm.anonymousAuth) }
    var macAddress by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PS_LightBlue,
        unfocusedBorderColor = PS_Blue,
        focusedTextColor = PS_White,
        unfocusedTextColor = PS_White,
        focusedLabelColor = PS_LightBlue,
        unfocusedLabelColor = PS_Blue
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PS_DarkBlue,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Save, null, tint = PS_LightBlue)
                Spacer(Modifier.width(8.dp))
                Text("Guardar Perfil", color = PS_White)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Guarda IP, puerto y credenciales para reusar esta conexión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PS_White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Nombre (ej: Living, Habitación)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = profileIp,
                        onValueChange = { profileIp = it },
                        label = { Text("IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = profilePort,
                        onValueChange = { profilePort = it },
                        label = { Text("Puerto") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = anonymous,
                        onCheckedChange = { anonymous = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = PS_Triangle,
                            uncheckedColor = PS_LightBlue
                        )
                    )
                    Text(
                        "Sin usuario/contraseña",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PS_White
                    )
                }
                if (!anonymous) {
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Usuario") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Contraseña") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = macAddress,
                    onValueChange = { macAddress = it },
                    label = { Text("MAC Address (Opcional para WoL)") },
                    placeholder = { Text("00:11:22:33:44:55") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (profileName.isNotBlank()) {
                        vm.saveProfile(
                            name = profileName,
                            mac = macAddress.ifBlank { null },
                            profileIp = profileIp,
                            profilePort = profilePort,
                            username = username,
                            password = password,
                            anonymous = anonymous
                        )
                        onDismiss()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
            ) { Text("Guardar", color = PS_White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = PS_Circle) }
        }
    )
}