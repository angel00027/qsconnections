package com.example.qsconnection

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import java.io.File
import com.example.qsconnection.ui.theme.*

enum class Screen {
    Main,
    PayloadSender,
    Ftp,
    Transfers,
    Installer,
    Connections
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var currentScreen by remember { mutableStateOf(Screen.Main) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showWolDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var macAddress by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val profiles by vm.profiles.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // El modo (Payload vs FTP) se decide por la pantalla destino, no por un
    // selector aparte: el modo FTP solo se activa al abrir el explorador FTP.
    fun navigateTo(screen: Screen) {
        vm.toggleMode(screen == Screen.Ftp)
        currentScreen = screen
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { event ->
            when (event) {
                is MainViewModel.UiEvent.ShowSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = "Copiar",
                        duration = if (event.isError) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        clipboard.setText(AnnotatedString(event.message))
                    }
                }
                MainViewModel.UiEvent.GoToTransfers -> {
                    currentScreen = Screen.Transfers
                }
            }
        }
    }

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(profiles) {
        vm.checkPings()
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = PS_DarkBlue,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = PS_LightBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("Acerca de", color = PS_White)
                }
            },
            text = {
                Column {
                    Text("QsConnections", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = PS_Triangle)
                    Text("Versión 1.0.0", style = MaterialTheme.typography.bodyMedium, color = PS_White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Herramienta todo-en-uno para tu consola: envía payloads, explora archivos por FTP, " +
                            "instala PKG y transfiere archivos por tu red local.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PS_White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Compatible con PS3, PS4 y PS5.", style = MaterialTheme.typography.labelSmall, color = PS_LightBlue)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Cerrar", color = PS_Triangle) }
            }
        )
    }

    if (showWolDialog) {
        AlertDialog(
            onDismissRequest = { showWolDialog = false },
            containerColor = PS_DarkBlue,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, null, tint = PS_Circle)
                    Spacer(Modifier.width(8.dp))
                    Text("Wake-on-LAN", color = PS_White)
                }
            },
            text = {
                Column {
                    Text(
                        "Introduce la dirección MAC de la consola que deseas encender.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PS_White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = macAddress,
                        onValueChange = { macAddress = it },
                        label = { Text("MAC Address") },
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

                    if (profiles.any { !it.mac.isNullOrBlank() }) {
                        Spacer(Modifier.height(16.dp))
                        Text("Perfiles guardados:", style = MaterialTheme.typography.labelMedium, color = PS_Triangle)
                        LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                            items(profiles.filter { !it.mac.isNullOrBlank() }) { profile ->
                                TextButton(
                                    onClick = { macAddress = profile.mac!! },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${profile.name} (${profile.mac})", style = MaterialTheme.typography.bodySmall, color = PS_White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (macAddress.isNotBlank()) {
                            vm.wakeOnLan(macAddress)
                            showWolDialog = false
                            macAddress = ""
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                ) { Text("Encender", color = PS_White) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWolDialog = false
                    macAddress = ""
                }) { Text("Cancelar", color = PS_Circle) }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = PS_DarkBlue,
            title = { Text("Configuración de la App", color = PS_White) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.soundEnabled = !vm.soundEnabled }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Efectos de sonido", color = PS_White)
                        Switch(
                            checked = vm.soundEnabled,
                            onCheckedChange = { vm.soundEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PS_Triangle,
                                checkedTrackColor = PS_Triangle.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.hapticEnabled = !vm.hapticEnabled }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Vibración haptica", color = PS_White)
                        Switch(
                            checked = vm.hapticEnabled,
                            onCheckedChange = { vm.hapticEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PS_Triangle,
                                checkedTrackColor = PS_Triangle.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSettingsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = PS_Triangle)
                ) { Text("Aceptar", color = PS_White) }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.logo_background),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Opciones",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Text(
                        "MENÚ",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    NavigationDrawerItem(
                        label = { Text("Inicio") },
                        selected = currentScreen == Screen.Main,
                        onClick = { navigateTo(Screen.Main) },
                        icon = { Icon(Icons.Default.Home, null) }
                    )

                    NavigationDrawerItem(
                        label = { Text("Perfil") },
                        selected = currentScreen == Screen.Connections,
                        onClick = { navigateTo(Screen.Connections) },
                        icon = { Icon(Icons.Default.Router, null) }
                    )

                    NavigationDrawerItem(
                        label = { Text("Payload Sender") },
                        selected = currentScreen == Screen.PayloadSender,
                        onClick = { navigateTo(Screen.PayloadSender) },
                        icon = { Icon(Icons.Default.FilePresent, null) }
                    )

                    NavigationDrawerItem(
                        label = { Text("Explorador FTP") },
                        selected = currentScreen == Screen.Ftp,
                        onClick = { navigateTo(Screen.Ftp) },
                        icon = { Icon(Icons.Default.Folder, null) }
                    )

                    NavigationDrawerItem(
                        label = { Text("Instalador PKG") },
                        selected = currentScreen == Screen.Installer,
                        onClick = { navigateTo(Screen.Installer) },
                        icon = { Icon(Icons.Default.SystemUpdate, null) }
                    )

                    NavigationDrawerItem(
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Transferencias")
                                if (vm.isSending || vm.isInstalling || vm.serverRunning) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge { Text("!") }
                                }
                            }
                        },
                        selected = currentScreen == Screen.Transfers,
                        onClick = { navigateTo(Screen.Transfers) },
                        icon = { Icon(Icons.Default.CloudUpload, null) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    NavigationDrawerItem(
                        label = { Text("Compartir Aplicación") },
                        selected = false,
                        icon = { Icon(Icons.Default.Share, null) },
                        onClick = {
                            try {
                                val sourceFile = File(context.applicationInfo.sourceDir)
                                val apkUri: Uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    sourceFile
                                )

                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_STREAM, apkUri)
                                    type = "application/vnd.android.package-archive"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                context.startActivity(Intent.createChooser(sendIntent, "Compartir instalador (APK)"))
                            } catch (e: Exception) {
                                vm.log("[ERROR] No se pudo compartir el APK: ${e.message}")
                            }
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        label = { Text("Configuración") },
                        selected = false,
                        icon = { Icon(Icons.Default.Settings, null) },
                        onClick = {
                            showSettingsDialog = true
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        label = { Text("Acerca de") },
                        selected = false,
                        icon = { Icon(Icons.Default.Info, null) },
                        onClick = {
                            showAboutDialog = true
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = PS_DarkBlue,
                        contentColor = PS_White,
                        actionColor = PS_Triangle,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, "Menú")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.logo_background),
                                contentDescription = "Logo App",
                                modifier = Modifier.size(40.dp),
                                tint = Color.Unspecified
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                when (currentScreen) {
                                    Screen.Main -> "Inicio"
                                    Screen.PayloadSender -> "Payload Sender"
                                    Screen.Ftp -> "Explorador FTP"
                                    Screen.Transfers -> "Transferencias"
                                    Screen.Installer -> "Instalador PKG"
                                    Screen.Connections -> "Perfil"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + slideInHorizontally(animationSpec = tween(300)) { it / 2 } togetherWith
                    fadeOut(animationSpec = tween(300)) + slideOutHorizontally(animationSpec = tween(300)) { -it / 2 }
                },
                label = "ScreenTransition",
                modifier = Modifier.padding(padding)
            ) { targetScreen ->
                when (targetScreen) {
                    Screen.Ftp -> Box(modifier = Modifier.fillMaxSize()) { FtpScreen(vm) }
                    Screen.Transfers -> Box(modifier = Modifier.fillMaxSize()) { TransfersScreen(vm) }
                    Screen.Installer -> Box(modifier = Modifier.fillMaxSize()) { InstallerScreen(vm) }
                    Screen.Connections -> Box(modifier = Modifier.fillMaxSize()) { ConnectionsScreen(vm) }
                    Screen.PayloadSender -> Box(modifier = Modifier.fillMaxSize()) {
                        PayloadSenderScreen(vm, onOpenConnections = { navigateTo(Screen.Connections) })
                    }
                    Screen.Main -> {
                        HomeContent(
                            vm = vm,
                            onOpenConnections = { navigateTo(Screen.Connections) },
                            onOpenPayload = { navigateTo(Screen.PayloadSender) },
                            onOpenFtp = { navigateTo(Screen.Ftp) },
                            onOpenInstaller = { navigateTo(Screen.Installer) },
                            onOpenTransfers = { navigateTo(Screen.Transfers) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeContent(
    vm: MainViewModel,
    onOpenConnections: () -> Unit,
    onOpenPayload: () -> Unit,
    onOpenFtp: () -> Unit,
    onOpenInstaller: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    val profiles by vm.profiles.collectAsState()

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 🔌 SECCIÓN DE CONEXIÓN
        SectionCard(title = "Conexión y perfil", icon = Icons.Default.Computer) {
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
                        Text("Perfiles y puertos", color = PS_Triangle)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { vm.testConnection() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !vm.isTesting && !vm.isSending && vm.ip.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PS_Blue)
            ) {
                if (vm.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PS_White)
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Probar conexión (${vm.port.ifBlank { "9020" }})")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ℹ️ QUÉ ES
        SectionCard(title = "¿Qué es esta aplicación?", icon = Icons.Default.Info) {
            Text(
                text = "QsConnections reúne en una sola app las herramientas que necesitas para " +
                    "trabajar con tu consola por tu red local, sin cables y sin PC.",
                style = MaterialTheme.typography.bodySmall,
                color = PS_White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(12.dp))
            FeatureLine(Icons.Default.Send, "Enviar payloads (.bin) a la consola por TCP.")
            FeatureLine(Icons.Default.Folder, "Explorar y gestionar archivos por FTP (PS3, PS4 y PS5).")
            FeatureLine(Icons.Default.SystemUpdate, "Instalar paquetes PKG: la consola descarga desde el teléfono.")
            FeatureLine(Icons.Default.CloudUpload, "Transferir archivos con progreso, pausa y reanudación.")
            FeatureLine(Icons.Default.PowerSettingsNew, "Encender la consola por Wake-on-LAN.")
        }

        Spacer(Modifier.height(16.dp))

        // 📋 ORDEN DE USO
        SectionCard(title = "Cómo usarla (orden de uso)", icon = Icons.Default.FormatListNumbered) {
            StepLine(1, "Ve a Perfil, escribe la IP de tu consola y guarda un perfil (con sus puertos y credenciales).")
            StepLine(2, "Pulsa “Probar conexión” para confirmar que el teléfono y la consola se ven en la red.")
            StepLine(3, "Envía un payload en Payload Sender o carga el que use tu consola.")
            StepLine(4, "Abre Explorador FTP para subir/descargar archivos del disco de la consola.")
            StepLine(5, "En Instalador PKG sirve e instala un paquete desde el teléfono.")
            StepLine(6, "Sigue todo en Transferencias: cualquier envío aparecerá ahí automáticamente.")
        }

        Spacer(Modifier.height(16.dp))

        // 🚀 ACCESOS RÁPIDOS
        SectionCard(title = "Accesos rápidos", icon = Icons.Default.Apps) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAccess("Payload Sender", Icons.Default.FilePresent, PS_Triangle, onOpenPayload, Modifier.weight(1f))
                QuickAccess("Explorador FTP", Icons.Default.Folder, PS_Blue, onOpenFtp, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAccess("Instalador PKG", Icons.Default.SystemUpdate, PS_Blue, onOpenInstaller, Modifier.weight(1f))
                QuickAccess("Transferencias", Icons.Default.CloudUpload, PS_Triangle, onOpenTransfers, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = PS_Triangle)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = PS_White.copy(alpha = 0.85f))
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = PS_Blue.copy(alpha = 0.3f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, PS_LightBlue.copy(alpha = 0.6f)),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PS_White)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = PS_White.copy(alpha = 0.85f))
    }
}

@Composable
private fun QuickAccess(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = PS_White
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = color)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = PS_DarkBlue.copy(alpha = 0.8f)),
        border = BorderStroke(1.dp, PS_Blue.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = PS_LightBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PS_White
                    )
                }
                action?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun LogEntry(log: String) {
    val color = when {
        log.contains("[ERROR]", true) -> PS_Circle
        log.contains("[OK]", true) || log.contains("[✔]", true) -> PS_Triangle
        log.contains("[+]", true) -> PS_LightBlue
        else -> PS_White.copy(alpha = 0.8f)
    }

    Text(
        text = log,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        ),
        color = color,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen()
    }
}