package com.example.qsconnection

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.net.Socket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.qsconnection.data.AppDatabase
import com.example.qsconnection.data.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // PKG incluido en la app (carpeta assets). Es el paquete que hay que
        // instalar una sola vez en la consola para habilitar las instalaciones.
        const val ESSENTIAL_PKG_NAME = "PS4_FLTZ00003_v1.02.pkg"
    }

    sealed class UiEvent {
        data class ShowSnackbar(val message: String, val isError: Boolean = false) : UiEvent()
        object GoToTransfers : UiEvent()
    }

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    private fun emitEvent(message: String, isError: Boolean = false) {
        viewModelScope.launch {
            _uiEvent.emit(UiEvent.ShowSnackbar(message, isError))
        }
    }

    private fun requestTransfers() {
        viewModelScope.launch {
            _uiEvent.emit(UiEvent.GoToTransfers)
        }
    }

    private val db = AppDatabase.getDatabase(application)
    private val profileDao = db.profileDao()

    var ip by mutableStateOf("")
    var port by mutableStateOf("9020")

    // Credenciales FTP (se guardan en el perfil)
    var ftpUsername by mutableStateOf("")
    var ftpPassword by mutableStateOf("")
    // true = el servidor no requiere usuario/contraseña (conexión anónima)
    var anonymousAuth by mutableStateOf(true)

    var isFtpMode by mutableStateOf(false) // Toggle para PS3 (FTP)
    var logs = mutableStateListOf<String>()
    var progress by mutableStateOf(0f)
    var status by mutableStateOf("Esperando...")
    var isSending by mutableStateOf(false)

    // Configuración
    var soundEnabled by mutableStateOf(true)
    var hapticEnabled by mutableStateOf(true)

    // Nuevas variables para una interfaz más amigable
    var transferSpeed by mutableStateOf("")
    var remainingTime by mutableStateOf("")
    var transferredSize by mutableStateOf("")
    var showUploadOverlay by mutableStateOf(false)
    
    private var lastUri: Uri? = null
    private var lastFileName: String? = null

    private var uploadJob: Job? = null

    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        activeTransfers.forEach { task ->
            if (task.job?.isActive == true) task.job?.cancel()
        }
        isSending = false
        showUploadOverlay = false
        status = "Cancelado"
        log("[!] Operación cancelada por el usuario")
    }

    class TransferTask(
        val id: String,
        val fileName: String,
        val isDownload: Boolean,
        val uri: Uri,
        val remotePath: String,
        progress: Float = 0f,
        status: String = "Pendiente",
        speed: String = "0 KB/s",
        remainingTime: String = "--:--",
        sizeInfo: String = "",
        var job: Job? = null
    ) {
        var progress by mutableStateOf(progress)
        var status by mutableStateOf(status)
        var speed by mutableStateOf(speed)
        var remainingTime by mutableStateOf(remainingTime)
        var sizeInfo by mutableStateOf(sizeInfo)
        var isPaused by mutableStateOf(false)
        // Punto exacto donde se cortó: permite pausar/reanudar y reconectar donde estaba
        @Volatile
        var bytesProcessed: Long = 0L
        var transferredBytes by mutableStateOf(0L)
        var totalBytes by mutableStateOf(-1L)
        var attempt by mutableStateOf(0)
        var canResume by mutableStateOf(true)

        val isRunning: Boolean
            get() = job?.isActive == true

        val isFinished: Boolean
            get() = status.contains("Completado") || status.contains("Error") || status.contains("Cancelado")

        // Se puede reanudar todo lo que no esté completado ni en marcha
        val isResumable: Boolean
            get() = !status.contains("Completado") && !isRunning
    }

    val activeTransfers = TransferManager.activeTransfers

    private val MAX_TRANSFER_ATTEMPTS = 4

    private class TransferPausedException : Exception()

    fun enqueueUpload(uri: Uri, context: Context, fileName: String, path: String = currentPath) {
        val taskId = java.util.UUID.randomUUID().toString()
        val task = TransferTask(taskId, fileName, false, uri, path)
        task.totalBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        activeTransfers.add(task)
        startTransfer(task, context)
        requestTransfers()
        // Por compatibilidad con el overlay actual si se desea
        showUploadOverlay = true
        isSending = true
    }

    fun enqueueDownload(fileName: String, context: Context, destinationUri: Uri, path: String = currentPath) {
        val taskId = java.util.UUID.randomUUID().toString()
        val task = TransferTask(taskId, fileName, true, destinationUri, path)
        activeTransfers.add(task)
        startTransfer(task, context)
        requestTransfers()
        // Por compatibilidad con el overlay actual si se desea
        showUploadOverlay = true
        isSending = true
    }

    fun resumeTask(task: TransferTask, context: Context) {
        if (task.status.contains("Completado")) return
        if (task.job?.isActive == true) {
            if (task.isPaused) togglePauseTask(task)
            return
        }
        if (!activeTransfers.contains(task)) activeTransfers.add(task)
        task.isPaused = false
        task.attempt = 0
        task.canResume = true
        startTransfer(task, context)
        showUploadOverlay = true
        isSending = true
        requestTransfers()
        log("[▶] Reanudando ${task.fileName} desde ${formatSize(task.bytesProcessed)}")
    }

    private fun startTransfer(task: TransferTask, context: Context) {
        task.isPaused = false
        task.job = TransferManager.managerScope.launch {
            val self = this.coroutineContext[Job]
            var attempt = 0
            try {
                while (isActive) {
                    if (task.isPaused) {
                        task.status = "Pausado"
                        task.speed = "0 KB/s"
                        task.remainingTime = "--:--"
                        while (isActive && task.isPaused) delay(250)
                        if (isActive) {
                            task.status = "Reanudando..."
                            attempt = 0
                        }
                        continue
                    }

                    try {
                        runTransfer(task, context)
                        break
                    } catch (e: CancellationException) {
                        task.status = "Cancelado"
                        throw e
                    } catch (e: TransferPausedException) {
                        // Se vuelve al inicio del bucle y se espera la reanudación
                    } catch (e: Exception) {
                        if (!isActive) {
                            task.status = "Cancelado"
                            break
                        }
                        attempt++
                        task.attempt = attempt
                        if (attempt >= MAX_TRANSFER_ATTEMPTS) {
                            task.status = "Error: ${e.message}"
                            task.speed = "0 KB/s"
                            task.canResume = true
                            playSound(context, R.raw.error)
                            vibrate(context, success = false)
                            emitEvent("Error en ${task.fileName}: ${e.message}", true)
                            log("[ERROR] ${task.fileName}: ${e.message} (reanudable desde ${formatSize(task.bytesProcessed)})")
                            break
                        }
                        task.status = "Reconectando... (intento $attempt/$MAX_TRANSFER_ATTEMPTS)"
                        log("[!] ${task.fileName}: ${e.message}. Continuando desde ${formatSize(task.bytesProcessed)}")
                        delay(1000L * attempt)
                    }
                }
            } finally {
                if (task.job === self) task.job = null
                val stillRunning = activeTransfers.any { it.job?.isActive == true && it != task }
                if (!stillRunning) {
                    updateService(context, "", 0, 0, true)
                    isSending = activeTransfers.any { it.job?.isActive == true }
                }
            }
        }
    }

    private suspend fun runTransfer(task: TransferTask, context: Context) {
        var ftpClient: FTPClient? = null
        try {
            task.status = "Conectando..."
            ftpClient = newTransferClient()

            if (!ftpClient.changeWorkingDirectory(task.remotePath)) {
                log("[!] No se pudo abrir ${task.remotePath}, se usará el directorio actual")
            }

            if (task.isDownload) prepareDownload(ftpClient, task, context)
            else prepareUpload(ftpClient, task, context)

            if (!ftpClient.completePendingCommand()) {
                throw Exception("Fallo al finalizar la transferencia")
            }

            task.status = "Completado ✅"
            task.progress = 1f
            task.speed = "0 KB/s"
            task.remainingTime = "00:00"
            task.isPaused = false
            task.canResume = false
            playSound(context, R.raw.success)
            vibrate(context, success = true)
            emitEvent("¡Transferencia de ${task.fileName} completada!")
            log("[✔] ${task.fileName}: ${formatSize(task.bytesProcessed)} transferidos")

            if (!task.isDownload && task.remotePath == currentPath && isFtpConnected) {
                refreshCurrentFolder()
            }
        } finally {
            try {
                ftpClient?.let { if (it.isConnected) { it.logout(); it.disconnect() } }
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun prepareDownload(ftp: FTPClient, task: TransferTask, context: Context) {
        val files = ftp.listFiles(task.fileName)
        val remoteSize = if (files != null && files.isNotEmpty()) files[0].size else -1L
        if (remoteSize > 0) task.totalBytes = remoteSize

        // El servidor es la referencia: sólo se reanuda si su archivo sigue ahí y es válido
        var offset = if (remoteSize > 0 && remoteSize >= task.bytesProcessed) task.bytesProcessed else 0L

        var output: java.io.OutputStream? = null
        if (offset > 0) {
            output = runCatching { context.contentResolver.openOutputStream(task.uri, "wa") }.getOrNull()
            if (output == null) offset = 0
        }
        if (offset > 0) {
            // REST: el servidor continúa la descarga desde ese byte
            ftp.setRestartOffset(offset)
        }
        if (output == null) {
            offset = 0
            ftp.setRestartOffset(0)
            output = context.contentResolver.openOutputStream(task.uri, "w")
                ?: throw Exception("Error al abrir el destino")
        }

        task.bytesProcessed = offset
        task.transferredBytes = offset

        val input = ftp.retrieveFileStream(task.fileName)
            ?: throw Exception("Error al iniciar la descarga")

        copyStream(input, output, task, context, offset)
    }

    private suspend fun prepareUpload(ftp: FTPClient, task: TransferTask, context: Context) {
        val fileSize = context.contentResolver.openAssetFileDescriptor(task.uri, "r")?.use { it.length } ?: -1L
        if (fileSize > 0) task.totalBytes = fileSize

        // El servidor indica cuánto ya tiene: sólo se reanuda si esta tarea ya había empezado
        // (si no, un STOR sobre un archivo existente debe truncarlo, no acumular datos)
        val remoteFiles = ftp.listFiles(task.fileName)
        var remoteSize = if (remoteFiles != null && remoteFiles.isNotEmpty()) remoteFiles[0].size else 0L
        var append = false

        if (remoteSize > 0 && task.bytesProcessed > 0) {
            if (fileSize > 0 && remoteSize > fileSize) {
                ftp.deleteFile(task.fileName)
                remoteSize = 0
            } else {
                append = true
            }
        }

        var input = context.contentResolver.openInputStream(task.uri)
            ?: throw Exception("Error al abrir el archivo origen")

        if (append && !skipFully(input, remoteSize)) {
            input.close()
            ftp.deleteFile(task.fileName)
            remoteSize = 0
            append = false
            input = context.contentResolver.openInputStream(task.uri)
                ?: throw Exception("Error al abrir el archivo origen")
        }

        task.bytesProcessed = if (append) remoteSize else 0L
        task.transferredBytes = task.bytesProcessed

        val output = if (append) ftp.appendFileStream(task.fileName) else ftp.storeFileStream(task.fileName)
        if (output == null) {
            input.close()
            throw Exception("No se pudo iniciar la subida en el servidor")
        }

        copyStream(input, output, task, context, task.bytesProcessed)
    }

    private fun skipFully(input: java.io.InputStream, count: Long): Boolean {
        if (count <= 0) return true
        val scratch = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private suspend fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        task: TransferTask,
        context: Context,
        startOffset: Long = 0L
    ) {
        val buffer = ByteArray(512 * 1024) // Buffer grande = menos llamadas al sistema = más velocidad
        var total = startOffset
        var sessionBytes = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdate = startTime
        val ctx = kotlin.coroutines.coroutineContext

        input.use { i ->
            output.use { o ->
                while (true) {
                    ctx.ensureActive()
                    if (task.isPaused) throw TransferPausedException()

                    val read = i.read(buffer)
                    if (read == -1) break

                    o.write(buffer, 0, read)
                    total += read
                    sessionBytes += read
                    task.bytesProcessed = total

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 500) {
                        lastUpdate = now
                        val elapsed = (now - startTime) / 1000.0
                        val bps = if (elapsed > 0) sessionBytes / elapsed else 0.0
                        withContext(Dispatchers.Main) {
                            task.transferredBytes = total
                            if (task.totalBytes > 0) {
                                task.progress = (total.toFloat() / task.totalBytes).coerceIn(0f, 1f)
                                val remainingBytes = task.totalBytes - total
                                val remainingSecs = if (bps > 0) (remainingBytes / bps).toLong() else 0L
                                task.remainingTime = formatDuration(remainingSecs)
                                task.sizeInfo = "${formatSize(total)} / ${formatSize(task.totalBytes)}"
                            } else {
                                task.progress = 0f
                                task.sizeInfo = formatSize(total)
                            }
                            task.speed = formatSpeed(bps)
                            task.status = if (task.isDownload) "Descargando..." else "Subiendo..."

                            // Reusamos las variables del overlay para compatibilidad con la UI actual si se desea
                            val running = activeTransfers.filter { it.job?.isActive == true && !it.isPaused }
                            if (running.isEmpty() || running.first() == task) {
                                this@MainViewModel.progress = task.progress
                                this@MainViewModel.transferSpeed = task.speed
                                this@MainViewModel.remainingTime = task.remainingTime
                                this@MainViewModel.transferredSize = task.sizeInfo
                                this@MainViewModel.status = task.status

                                val activeCount = running.size.coerceAtLeast(1)
                                val averageProgress = if (running.isNotEmpty()) {
                                    running.map { it.progress }.average() * 100
                                } else {
                                    task.progress * 100.0
                                }
                                updateService(context, task.fileName, averageProgress.toInt(), activeCount, false, task.speed, task.remainingTime)
                            }
                        }
                    }
                }
            }
        }
    }

private fun updateService(
        context: Context,
        fileName: String,
        progress: Int,
        activeCount: Int = 1,
        isDone: Boolean = false,
        speed: String = "",
        remaining: String = "",
        // "done" | "error" | "cancel": título del aviso final al terminar.
        result: String = "done"
    ) {
        val intent = Intent(context, TransferService::class.java).apply {
            putExtra("FILE_NAME", fileName)
            putExtra("PROGRESS", progress.coerceIn(0, 100))
            putExtra("ACTIVE_COUNT", activeCount)
            putExtra("IS_DONE", isDone)
            putExtra("SPEED", speed)
            putExtra("REMAINING_TIME", remaining)
            putExtra("RESULT", result)
        }
        try {
            if (isDone) {
                // Si el servicio no está en marcha no hay nada que notificar:
                // nunca se arranca un servicio en primer plano solo para cerrarlo
                // (esto era lo que podía cerrar la app con archivos pequeños).
                if (TransferService.running) context.startService(intent)
            } else if (TransferService.running) {
                // Ya está en primer plano: basta con actualizar la notificación.
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        } catch (e: Exception) {
// Una notificación fallida nunca debe tumbar la transferencia.
            log("[!] Aviso: no se pudo actualizar la notificación (${e.message})")
        }
    }

    private fun playSound(context: Context, resId: Int) {
        if (!soundEnabled) return
        try {
            val mediaPlayer = android.media.MediaPlayer.create(context, resId)
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.start()
        } catch (e: Exception) {
            // Ignorar fallos de sonido
        }
    }

    private fun vibrate(context: Context, success: Boolean) {
        if (!hapticEnabled) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (success) {
                // Doble toque para éxito
                VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1)
            } else {
                // Un toque largo para error
                VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            if (success) vibrator.vibrate(longArrayOf(0, 100, 100, 100), -1)
            else vibrator.vibrate(400)
        }
    }

    fun togglePauseTask(task: TransferTask) {
        if (task.job?.isActive != true) return
        task.isPaused = !task.isPaused
        if (task.isPaused) {
            log("[||] ${task.fileName}: pausando en ${formatSize(task.bytesProcessed)}")
        } else {
            log("[▶] ${task.fileName}: reanudando desde ${formatSize(task.bytesProcessed)}")
        }
    }

    fun cancelTask(task: TransferTask) {
        // Se queda en la lista para poder reanudarla más tarde
        task.isPaused = false
        task.job?.cancel()
        task.status = "Cancelado"
    }

    fun clearCompletedTasks() {
        activeTransfers.removeAll { it.isFinished }
    }

    fun removeTask(task: TransferTask) {
        task.isPaused = false
        task.job?.cancel()
        activeTransfers.remove(task)
    }

    var isTesting by mutableStateOf(false)

    var isScanning by mutableStateOf(false)
    var scanResults by mutableStateOf<List<ScanResult>>(emptyList())

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TransferService.ACTION_STOP_TRANSFERS) {
                cancelAllTransfers()
            }
        }
    }

    init {
        viewModelScope.launch {
            profileDao.getAllProfiles().collect {
                _profiles.value = it
            }
        }
        
        // Registrar receptor para detener transferencias desde la notificación
        val filter = android.content.IntentFilter(TransferService.ACTION_STOP_TRANSFERS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // En versiones anteriores a Android 13, no se requiere el flag pero es buena práctica
            // Sin embargo, para evitar el error del linter, usamos 0 o el flag si está disponible
            application.registerReceiver(stopReceiver, filter)
        }
    }

    fun cancelAllTransfers() {
        activeTransfers.forEach { it.job?.cancel() }
        uploadJob?.cancel()
        activeTransfers.clear()
        isSending = false
        showUploadOverlay = false
        status = "Transferencias detenidas"
        log("[!] Todas las transferencias han sido detenidas")
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(stopReceiver)
        } catch (e: Exception) {}
        // Cerrar la conexión FTP persistente
        TransferManager.managerScope.launch { ftpMutex.withLock { closeSharedFtp() } }
    }

    fun toggleMode(isFtp: Boolean) {
        if (isFtpMode == isFtp) return
        // Solo se restablece el puerto si seguía en el valor por defecto del
        // modo anterior; así no se pisa un puerto personalizado (p. ej. 2121).
        val wasDefault = port.trim() == (if (isFtp) "9020" else "21")
        isFtpMode = isFtp
        if (wasDefault) port = if (isFtp) "21" else "9020"
        log("[*] Modo cambiado a ${if (isFtp) "FTP (consola)" else "Payload (consola)"}")
    }

    fun scanNetwork(context: Context) {
        if (isScanning) return
        viewModelScope.launch(Dispatchers.IO) {
            isScanning = true
            scanResults = emptyList()
            status = "Escaneando red..."
            log("[...] Iniciando escaneo de red local...")

            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val connectionInfo = wifiManager.connectionInfo
                val ipAddress = connectionInfo.ipAddress

                if (ipAddress == 0) {
                    log("[!] No conectado a WiFi")
                    status = "Sin WiFi"
                    isScanning = false
                    return@launch
                }

                val prefix = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff
                )

                val portsToProbe = listOf(21, 9020, 9090, 12800, 2121, 3232)
                val found = java.util.Collections.synchronizedList(ArrayList<Pair<String, Int>>())
                val semaphore = java.util.concurrent.Semaphore(32)

                val workers = (1..254).map { i ->
                    launch {
                        try {
                            semaphore.acquire()
                            val host = prefix + i
                            for (p in portsToProbe) {
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress(host, p), 300)
                                    socket.close()
                                    found.add(host to p)
                                } catch (_: Exception) {
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                workers.forEach { it.join() }

                val grouped = found.sortedBy { it.first }
                    .groupBy { it.first }
                    .map { ScanResult(it.key, it.value.map { v -> v.second }.distinct().sorted()) }

                scanResults = grouped
                log("[OK] Escaneo terminado: ${grouped.size} dispositivo(s) con puertos abiertos")
                status = "Escaneo finalizado"

                val ps4 = grouped.firstOrNull { r ->
                    r.openPorts.any { it == 12800 || it == 9020 || it == 9090 }
                }
                if (ps4 != null) {
                    val preferred = ps4.openPorts.firstOrNull { it == 9020 || it == 9090 } ?: ps4.openPorts.first()
                    ip = ps4.ip
                    port = preferred.toString()
                    log("[+] Seleccionada ${ps4.ip} (puertos ${ps4.openPorts.joinToString(", ")})")
                    status = "Consola seleccionada: ${ps4.ip}"
                }
            } catch (e: Exception) {
                log("[ERROR] Fallo en el escaneo: ${e.message}")
            } finally {
                isScanning = false
            }
        }
    }

    fun selectScanResult(result: ScanResult) {
        val preferred = result.openPorts.firstOrNull { it == 9020 || it == 9090 || it == 12800 }
            ?: result.openPorts.firstOrNull()
        ip = result.ip
        preferred?.let { port = it.toString() }
        status = "Seleccionada ${result.ip}"
        log("[+] Usando ${result.ip}")
    }

    fun sendFile(uri: Uri, context: Context, fileName: String?) {
        showUploadOverlay = true
        if (isFtpMode) {
            sendFTP(uri, context, fileName ?: "archivo.pkg")
        } else {
            sendPayload(uri, context, fileName)
        }
    }

    private fun sendFTP(uri: Uri, context: Context, fileName: String) {
        // Se reutiliza el mismo motor de transferencias (reintentos, progreso y reanudación)
        log("[...] Subiendo $fileName a la consola (/dev_hdd0/packages)...")
        enqueueUpload(uri, context, fileName, path = "/dev_hdd0/packages")
    }

    var ftpFiles = mutableStateListOf<org.apache.commons.net.ftp.FTPFile>()
    var currentPath by mutableStateOf("/")
    var isFtpConnected by mutableStateOf(false)
    var isFtpLoading by mutableStateOf(false)

    // Perfiles y Pings
    private val _pings = mutableStateMapOf<Int, String>()
    val pings: Map<Int, String> = _pings

    fun checkPings() {
        profiles.value.forEach { profile ->
            viewModelScope.launch(Dispatchers.IO) {
                val targetPort = profile.port.toIntOrNull() ?: 21
                try {
                    val socket = Socket()
                    val startTime = System.currentTimeMillis()
                    socket.connect(java.net.InetSocketAddress(profile.ip, targetPort), 1500)
                    val endTime = System.currentTimeMillis()
                    socket.close()
                    _pings[profile.id] = "${endTime - startTime}ms"
                } catch (e: Exception) {
                    _pings[profile.id] = "OFF"
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Conexión FTP
    // ------------------------------------------------------------------
    // Conexión persistente para navegar: no hay que reconectar en cada
    // operación (mucho más rápido). Las transferencias usan su propia
    // conexión para no bloquear la exploración.
    private var sharedFtp: FTPClient? = null
    private var sharedFtpKey: String? = null
    private val ftpMutex = Mutex()

    private fun currentFtpKey(): String =
        "${ip.trim()}:${port.trim()}|${if (anonymousAuth) "anon" else "auth"}|${ftpUsername.trim()}|$ftpPassword"

    private fun createFtpClient(): FTPClient {
        val ftpClient = FTPClient()
        // Optimización de buffers para redes locales rápidas
        ftpClient.bufferSize = 512 * 1024
        ftpClient.setSendBufferSize(512 * 1024)
        ftpClient.setReceiveBufferSize(512 * 1024)
        ftpClient.setSendDataSocketBufferSize(512 * 1024)
        ftpClient.setReceieveDataSocketBufferSize(512 * 1024)

        // Aumentamos los tiempos de espera como hace FileZilla
        ftpClient.connectTimeout = 10000
        ftpClient.defaultTimeout = 10000
        ftpClient.controlEncoding = "UTF-8"

        val targetIp = ip.trim()
        if (targetIp.isEmpty()) throw Exception("No has escrito ninguna IP.")
        val targetPort = port.trim().toIntOrNull() ?: 21
        ftpClient.connect(targetIp, targetPort)

        val loggedIn = if (anonymousAuth) {
            ftpClient.login("anonymous", "")
        } else {
            ftpClient.login(ftpUsername.trim(), ftpPassword)
        }
        if (!loggedIn) {
            try {
                ftpClient.disconnect()
            } catch (e: Exception) {
            }
            throw Exception(
                if (anonymousAuth) "El servidor rechazó la conexión anónima."
                else "Usuario o contraseña incorrectos."
            )
        }

        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        ftpClient.setDataTimeout(10000)
        return ftpClient
    }

    private fun closeSharedFtp() {
        val client = sharedFtp
        sharedFtp = null
        sharedFtpKey = null
        try {
            client?.let { if (it.isConnected) { it.logout(); it.disconnect() } }
        } catch (e: Exception) {
        }
    }

    private suspend fun <T> withSharedFtp(forceReconnect: Boolean = false, block: suspend (FTPClient) -> T): T {
        return ftpMutex.withLock {
            if (forceReconnect) closeSharedFtp()
            val key = currentFtpKey()
            var client = sharedFtp
            if (client != null && sharedFtpKey == key) {
                val healthy = try {
                    client.sendNoOp()
                    client.isConnected
                } catch (e: Exception) {
                    false
                }
                if (!healthy) {
                    closeSharedFtp()
                    client = null
                }
            } else {
                closeSharedFtp()
                client = null
            }
            if (client == null) {
                client = createFtpClient()
                sharedFtp = client
                sharedFtpKey = key
            }
            block(client)
        }
    }

    // Las transferencias usan una conexión propia; si el servidor sólo
    // admite una, se libera la de navegación y se reintenta.
    private suspend fun newTransferClient(): FTPClient {
        return try {
            createFtpClient()
        } catch (e: Exception) {
            ftpMutex.withLock { closeSharedFtp() }
            createFtpClient()
        }
    }

    fun disconnectFTP() {
        viewModelScope.launch(Dispatchers.IO) {
            ftpMutex.withLock { closeSharedFtp() }
        }
        isFtpConnected = false
        status = "Desconectado"
        log("[*] Conexión FTP cerrada")
    }

    private suspend fun refreshCurrentFolder() {
        try {
            withSharedFtp { client ->
                client.changeWorkingDirectory(currentPath)
                val files = client.listFiles()
                withContext(Dispatchers.Main) {
                    ftpFiles.clear()
                    ftpFiles.addAll(files ?: emptyArray())
                }
            }
        } catch (e: Exception) {
            log("[!] Error al refrescar lista: ${e.message}")
        }
    }

    fun connectFTP() {
        if (ip.isBlank()) {
            log("[!] Error: No has escrito ninguna IP.")
            status = "IP necesaria"
            return
        }

        // Iniciamos en el hilo principal para feedback inmediato
        isFtpLoading = true
        status = "Conectando..."
        log("[*] Conectando a $ip:$port${if (anonymousAuth) "" else " (usuario: ${ftpUsername.trim().ifEmpty { "—" }})"} ...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withSharedFtp(forceReconnect = true) { client ->
                    val target = if (currentPath.isBlank() || currentPath == "/") "/" else currentPath
                    if (target != "/") {
                        if (!client.changeWorkingDirectory(target)) {
                            log("[!] No se pudo acceder a $target, volviendo a raíz.")
                            client.changeWorkingDirectory("/")
                        }
                    }

                    val files = client.listFiles()
                    val workingDir = client.printWorkingDirectory() ?: target

                    withContext(Dispatchers.Main) {
                        ftpFiles.clear()
                        ftpFiles.addAll(files ?: emptyArray())
                        currentPath = workingDir
                        isFtpConnected = true
                        status = "Conectado ✅"
                        log("[OK] Conexión establecida con éxito.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log("[ERROR] FTP: ${e.message}")
                    status = "Error de conexión"
                    isFtpConnected = false
                }
            } finally {
                isFtpLoading = false
            }
        }
    }

    fun cdFTP(path: String) {
        isFtpLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetPath = if (path == "..") {
                    currentPath.substringBeforeLast("/", "").ifEmpty { "/" }
                } else {
                    if (currentPath.endsWith("/")) "$currentPath$path" else "$currentPath/$path"
                }

                withSharedFtp { ftpClient ->
                    if (ftpClient.changeWorkingDirectory(targetPath)) {
                        val newDir = ftpClient.printWorkingDirectory() ?: targetPath
                        val files = ftpClient.listFiles()
                        withContext(Dispatchers.Main) {
                            currentPath = newDir
                            ftpFiles.clear()
                            ftpFiles.addAll(files ?: emptyArray())
                        }
                    } else {
                        log("[!] No se pudo abrir $targetPath")
                    }
                }
            } catch (e: Exception) {
                log("[ERROR] CD: ${e.message}")
            } finally {
                isFtpLoading = false
            }
        }
    }

    fun retryUpload(context: Context) {
        // Reanuda la última transferencia que falló o se pausó
        val pending = activeTransfers.lastOrNull { it.isResumable }
        if (pending != null) {
            resumeTask(pending, context)
            return
        }
        val uri = lastUri
        val name = lastFileName
        if (uri != null && name != null) {
            if (isFtpMode) {
                enqueueUpload(uri, context, name)
            } else {
                sendFile(uri, context, name)
            }
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec < 1024) return String.format("%.1f B/s", bytesPerSec)
        val kbps = bytesPerSec / 1024.0
        if (kbps < 1024) return String.format("%.1f KB/s", kbps)
        val mbps = kbps / 1024.0
        return String.format("%.2f MB/s", mbps)
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun deleteRecursive(ftpClient: FTPClient, path: String): Boolean {
        val files = ftpClient.listFiles(path) ?: return false
        for (file in files) {
            val fileName = file.name
            if (fileName == "." || fileName == "..") continue

            val fullPath = if (path.endsWith("/")) "$path$fileName" else "$path/$fileName"
            if (file.isDirectory) {
                if (!deleteRecursive(ftpClient, fullPath)) return false
            } else {
                if (!ftpClient.deleteFile(fullPath)) return false
            }
        }
        return ftpClient.removeDirectory(path)
    }

    fun deleteFTPFile(fileName: String, isDirectory: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withSharedFtp { ftpClient ->
                    ftpClient.changeWorkingDirectory(currentPath)

                    val success = if (isDirectory) {
                        deleteRecursive(ftpClient, fileName)
                    } else {
                        ftpClient.deleteFile(fileName)
                    }

                    if (success) {
                        log("[✔] Eliminado: $fileName")
                        val files = ftpClient.listFiles()
                        withContext(Dispatchers.Main) {
                            ftpFiles.clear()
                            ftpFiles.addAll(files ?: emptyArray())
                        }
                    } else {
                        log("[!] No se pudo eliminar $fileName")
                    }
                }
            } catch (e: Exception) {
                log("[ERROR] Delete: ${e.message}")
            }
        }
    }

    fun renameFTPFile(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withSharedFtp { ftpClient ->
                    ftpClient.changeWorkingDirectory(currentPath)
                    if (ftpClient.rename(oldName, newName)) {
                        log("[✔] Renombrado: $oldName -> $newName")
                        val files = ftpClient.listFiles()
                        withContext(Dispatchers.Main) {
                            ftpFiles.clear()
                            ftpFiles.addAll(files ?: emptyArray())
                        }
                    } else {
                        log("[!] No se pudo renombrar $oldName")
                    }
                }
            } catch (e: Exception) {
                log("[ERROR] Rename: ${e.message}")
            }
        }
    }

    fun saveProfile(
        name: String,
        mac: String? = null,
        profileIp: String = ip,
        profilePort: String = port,
        username: String? = null,
        password: String? = null,
        anonymous: Boolean = anonymousAuth
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            log("[!] El perfil necesita un nombre")
            return
        }
        val cleanIp = profileIp.trim()
        if (!isValidIp(cleanIp)) {
            log("[!] IP no válida: $profileIp")
            return
        }
        val cleanPort = profilePort.trim().ifEmpty { "21" }
        viewModelScope.launch(Dispatchers.IO) {
            val newProfile = Profile(
                name = cleanName,
                ip = cleanIp,
                port = cleanPort,
                lastUsed = System.currentTimeMillis(),
                mac = mac?.trim()?.ifEmpty { null },
                username = if (anonymous) null else username?.trim()?.takeIf { it.isNotEmpty() },
                password = if (anonymous) null else password,
                anonymous = anonymous
            )
            profileDao.insertProfile(newProfile)
            log("[+] Perfil '$cleanName' guardado ($cleanIp:$cleanPort${if (anonymous) "" else ", usuario ${newProfile.username ?: "—"}"})")
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            profileDao.deleteProfile(profile)
            log("[-] Perfil '${profile.name}' eliminado")
        }
    }

    fun selectProfile(profile: Profile) {
        val previousKey = currentFtpKey()
        ip = profile.ip
        port = profile.port
        ftpUsername = profile.username ?: ""
        ftpPassword = profile.password ?: ""
        anonymousAuth = profile.anonymous
        val newKey = currentFtpKey()

        if (previousKey != newKey) {
            // La conexión abierta era de otra dirección/credenciales: se cierra
            viewModelScope.launch(Dispatchers.IO) {
                ftpMutex.withLock { if (sharedFtpKey != newKey) closeSharedFtp() }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            profileDao.insertProfile(profile.copy(lastUsed = System.currentTimeMillis()))
        }
        log(
            "[*] Perfil '${profile.name}' cargado (${profile.ip}:${profile.port}" +
                if (profile.anonymous) ")" else ", usuario ${profile.username ?: "—"})"
        )
    }

    fun clearLogs() {
        logs.clear()
        log("[*] Registros limpiados")
    }

    fun wakeOnLan(mac: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("[...] Enviando paquete Magic Packet a $mac...")
                val macBytes = getMacBytes(mac)
                val bytes = ByteArray(6 + 16 * macBytes.size)
                for (i in 0..5) {
                    bytes[i] = 0xff.toByte()
                }
                for (i in 1..16) {
                    System.arraycopy(macBytes, 0, bytes, i * 6, 6)
                }

                val address = java.net.InetAddress.getByName("255.255.255.255")
                val packet = java.net.DatagramPacket(bytes, bytes.size, address, 9)
                val socket = java.net.DatagramSocket()
                socket.send(packet)
                socket.close()
                log("[✔] Paquete Wake-on-LAN enviado.")
            } catch (e: Exception) {
                log("[ERROR] WoL: ${e.message}")
            }
        }
    }

    private fun getMacBytes(mac: String): ByteArray {
        val bytes = ByteArray(6)
        val hex = mac.split(":", "-")
        if (hex.size != 6) throw IllegalArgumentException("MAC address inválida")
        for (i in 0..5) {
            bytes[i] = hex[i].toInt(16).toByte()
        }
        return bytes
    }

    private fun isValidIp(ip: String): Boolean {
        val pattern = "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$".toRegex()
        return pattern.matches(ip)
    }

    fun log(msg: String) {
        if (logs.size > 100) logs.removeAt(0)
        logs.add(msg)
    }

    fun testConnection() {
        testPort(port.toIntOrNull() ?: 9020)
    }

    fun testPkgConnection() {
        testPort(12800)
    }

    fun testPort(portToTest: Int) {
        if (isTesting || isSending) return

        if (ip.isBlank()) {
            status = "IP vacía"
            log("[!] Introduce la dirección IP de la consola")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            isTesting = true
            lastInstallTest = null
            val isApi = portToTest == 12800
            try {
                status = "Probando puerto $portToTest..."
                log(if (isApi) "[...] Comprobando HTTP en $ip:$portToTest..." else "[...] Conectando a $ip:$portToTest (timeout 4s)...")

                if (isApi) {
                    val connection = java.net.URL("http://$ip:$portToTest/").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    val code = connection.responseCode
                    connection.disconnect()

                    status = "API de instalación accesible ✅"
                    log("[OK] El servidor HTTP de 12800 respondió (código $code)")
                    lastInstallTest = InstallTestResult(
                        "La API de instalación responde por HTTP (código $code). Puedes instalar el PKG.",
                        ok = true,
                        port = "$portToTest"
                    )
                } else {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(ip, portToTest), 4000)
                    socket.close()

                    status = "Conexión exitosa ✅"
                    log("[OK] $ip:$portToTest acepta conexiones")
                    lastInstallTest = InstallTestResult(
                        "Conexión establecida (TCP) con $ip en el puerto $portToTest.",
                        ok = true,
                        port = "$portToTest"
                    )
                }
            } catch (e: Exception) {
                status = "Sin respuesta ❌"
                log("[ERROR] $ip:$portToTest no responde: ${e.message}")
                lastInstallTest = InstallTestResult(
                    if (isApi) {
                        "El puerto 12800 no responde por HTTP. Comprueba que la PS4 tenga etaHEN o Remote Package Installer activo y esté en la misma red. (${e.message})"
                    } else {
                        "No hay respuesta en $ip:$portToTest. Comprueba que la consola esté encendida y en la misma red. (${e.message})"
                    },
                    ok = false,
                    port = "$portToTest"
                )
            } finally {
                isTesting = false
            }
        }
    }

    fun sendPayload(uri: Uri, context: Context, fileName: String? = null) {
        if (ip.isBlank()) {
            status = "IP necesaria"
            log("[!] Configura la IP antes de enviar")
            return
        }
        requestTransfers()
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                isSending = true
                status = "Iniciando envío..."
                progress = 0f

                val s = Socket()
                socket = s
                // Buffer de envío grande = menos esperas de red = payload más rápido
                s.sendBufferSize = 512 * 1024
                s.tcpNoDelay = true
                s.connect(java.net.InetSocketAddress(ip, port.toIntOrNull() ?: 9020), 10000)
                val output = s.getOutputStream()
                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("No se pudo abrir el archivo")

                val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                log("[...] Enviando payload (${fileSize / 1024} KB)...")

                val buffer = ByteArray(512 * 1024)
                var totalSent = 0L
                val startTime = System.currentTimeMillis()
                var lastUpdate = startTime

                inputStream.use { input ->
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalSent += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 250) {
                            lastUpdate = now
                            val elapsed = (now - startTime) / 1000.0
                            if (elapsed > 0) transferSpeed = formatSpeed(totalSent / elapsed)
                            if (fileSize > 0) {
                                progress = (totalSent.toFloat() / fileSize).coerceIn(0f, 1f)
                                transferredSize = "${formatSize(totalSent)} / ${formatSize(fileSize)}"
                            }
                            fileName?.let { updateService(context, it, (progress * 100).toInt()) }
                        }
                    }
                }
                
                output.flush()

                if (!isActive) {
                    log("[!] Envío de payload cancelado")
                    return@launch
                }

                status = "¡Payload enviado! ✅"
                log("[✔] Transferencia completada con éxito")
                playSound(context, R.raw.success)
                vibrate(context, success = true)
                fileName?.let { 
                    updateService(context, it, 100, isDone = true)
                    emitEvent("¡Payload $it enviado!")
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    status = "Cancelado"
                } else {
                    status = "Error en el envío ❌"
                    log("[ERROR] Falló la transferencia: ${e.message}")
                    playSound(context, R.raw.error)
                    vibrate(context, success = false)
                    emitEvent("Error en el envío: ${e.message}", true)
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
                isSending = false
                uploadJob = null
            }
        }
    }

    // ===================== INSTALADOR DE PKG =====================
    // La PS4 descarga el PKG desde este teléfono (servidor HTTP) usando la
    // API del puerto 12800: etaHEN (POST /upload) o Remote Package Installer
    // (POST /api/install). Mientras se sirve el archivo se muestra el % real.

    var isInstalling by mutableStateOf(false)
    var installIndeterminate by mutableStateOf(false)
    var installUrl by mutableStateOf("")
    var installMethod by mutableStateOf("")
    var installFileName by mutableStateOf("")
    var installServerPort by mutableStateOf(PkgHttpServer.DEFAULT_PORT.toString())

    var lastInstallTest by mutableStateOf<InstallTestResult?>(null)

    // Si la última instalación falló, permite reintentar/reanudar sin volver a
    // elegir el archivo (el PKG sigue seleccionado).
    var installFailed by mutableStateOf(false)
    var installError by mutableStateOf("")

    // PKG seleccionado en la pantalla de instalación (sobrevive a los cambios de pantalla)
    var pendingPkgUri by mutableStateOf<Uri?>(null)
    var pendingPkgName by mutableStateOf<String?>(null)
    var pendingPkgSize by mutableStateOf(0L)

    // Servidor local independiente (el teléfono sirve el .pkg sin enviar orden a la PS4)
    var serverRunning by mutableStateOf(false)
    var serverUrl by mutableStateOf("")
    var serverFileName by mutableStateOf("")
    var serverClientConnected by mutableStateOf(false)
    private var serverJob: Job? = null

    private var pkgServer: PkgHttpServer? = null
    private var installJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        pkgServer?.stop()
        pkgServer = null
        releaseWakeLock()
        isInstalling = false
        installIndeterminate = false
        isSending = false
        status = "Instalación cancelada"
        log("[!] Instalación cancelada por el usuario")
    }

    /**
     * Guarda el PKG esencial incluido en la app (carpeta assets) en la carpeta
     * de Descargas del teléfono, para poder subirlo por FTP e instalarlo una vez.
     */
    fun saveEssentialPkg(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveAssetToDownloads(context, ESSENTIAL_PKG_NAME)
                log("[✔] PKG esencial guardado en Descargas/$ESSENTIAL_PKG_NAME")
                emitEvent("PKG esencial guardado en Descargas/$ESSENTIAL_PKG_NAME. Ya puedes subirlo por FTP.")
            } catch (e: Exception) {
                log("[ERROR] Guardando PKG esencial: ${e.message}")
                emitEvent("No se pudo guardar el PKG esencial: ${e.message}", true)
            }
        }
    }

    /** Copia un asset a la carpeta pública de Descargas (sin permisos en Android 10+). */
    private fun saveAssetToDownloads(context: Context, assetName: String) {
        context.assets.open(assetName).use { input ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Elimina una copia anterior con el mismo nombre (si es posible).
                try {
                    resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(assetName)
                    )
                } catch (_: Exception) {
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, assetName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                    ?: throw Exception("No se pudo crear el archivo en Descargas")
                resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                    ?: throw Exception("No se pudo escribir en Descargas")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, assetName)
                FileOutputStream(file).use { out -> input.copyTo(out) }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        }
    }

    fun startServer(context: Context, uri: Uri?, fileName: String?) {
        if (serverRunning) {
            emitEvent("El servidor ya está en marcha", true)
            return
        }
        if (isSending || isInstalling) {
            emitEvent("Termina la operación en curso", true)
            return
        }
        if (uri == null) {
            emitEvent("Selecciona primero un archivo .pkg", true)
            return
        }

        requestTransfers()
        serverJob = viewModelScope.launch(Dispatchers.IO) {
            var server: PkgHttpServer? = null
            var lastNotifyMs = System.currentTimeMillis()
            var lastNotifyBytes = 0L
            try {
                val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (total <= 0) throw Exception("No se pudo leer el tamaño del PKG")

                val localIp = PkgHttpServer.localIpAddress(ip.takeIf { it.isNotBlank() })
                    ?: throw Exception("No se obtuvo la IP del teléfono (¿conectado a WiFi?)")

                progress = 0f
                transferSpeed = "0 KB/s"
                remainingTime = "--:--"
                transferredSize = ""
                status = "Preparando servidor local..."

                server = PkgHttpServer(
                    onProgress = { sent, totalBytes ->
                        if (totalBytes > 0) {
                            progress = (sent.toFloat() / totalBytes).coerceIn(0f, 1f)
                        }
                        transferredSize = "${formatSize(sent)} / ${formatSize(totalBytes)}"
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastNotifyMs) / 1000.0
                        if (elapsed >= 1.0) {
                            val bps = (sent - lastNotifyBytes) / elapsed
                            if (bps > 0) {
                                transferSpeed = formatSpeed(bps)
                                remainingTime = formatDuration(((totalBytes - sent) / bps).toLong())
                            }
                            lastNotifyMs = now
                            lastNotifyBytes = sent
                        }
                    },
                    onComplete = {
                        status = "Servidor: la PS4 terminó de descargar"
                        log("[✔] La PS4 terminó de descargar el PKG del servidor")
                    },
                    onClientConnected = {
                        serverClientConnected = true
                        status = "La PS4 está descargando..."
                        log("[*] La PS4 conectó al servidor local")
                    }
                )

                if (!server.start(context, uri, total, fileName ?: "paquete.pkg", installServerPort.toIntOrNull() ?: PkgHttpServer.DEFAULT_PORT)) {
                    throw Exception("No se pudo abrir el puerto ${installServerPort.ifBlank { PkgHttpServer.DEFAULT_PORT.toString() }}")
                }
                pkgServer = server
                serverRunning = true
                serverClientConnected = false
                serverFileName = fileName ?: "paquete.pkg"
                serverUrl = "http://$localIp:${server.boundPort}/pkg"
                log("[*] Servidor iniciado: $serverUrl")
                status = "Servidor en marcha"

                while (serverRunning) {
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: Exception) {
                status = "Servidor detenido: ${e.message}"
                log("[ERROR] Servidor: ${e.message}")
                emitEvent("Servidor: ${e.message}", true)
            } finally {
                server?.stop()
                if (pkgServer === server) pkgServer = null
                serverRunning = false
                serverUrl = ""
                serverClientConnected = false
            }
        }
    }

    fun stopServer() {
        serverRunning = false
        serverJob?.cancel()
        pkgServer?.stop()
        serverUrl = ""
        serverClientConnected = false
        log("[*] Servidor detenido")
        status = "Servidor detenido"
    }

    fun installPkg(uri: Uri, fileName: String) {
        // Contexto de aplicación: la instalación sigue viva aunque se cierre la
        // Activity, así no se interrumpe al salir de la app.
        val context = getApplication<Application>()

        // Si el servidor local manual está activo, lo detenemos solos:
        // el propio proceso de instalación levanta su servidor. Así el usuario
        // no tiene que ir a la tarjeta "Servidor local" a pararlo.
        if (serverRunning) {
            stopServer()
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        if (ip.isBlank()) {
            status = "IP necesaria"
            log("[!] Configura la IP de la PS4 antes de instalar")
            return
        }

        if (isSending || isInstalling) {
            emitEvent("Ya hay una operación en curso", true)
            return
        }

        installJob?.cancel()
        // Se lanza en un scope de proceso (no del ViewModel) para que la
        // instalación continúe aunque el usuario salga de la app. La
        // notificación en primer plano impide además que Android mate el
        // proceso mientras se sirve el PKG.
        installJob = TransferManager.managerScope.launch {
            installFailed = false
            installError = ""
            isInstalling = true
            isSending = true
            showUploadOverlay = true
            installIndeterminate = true
            installFileName = fileName
            installUrl = ""
            installMethod = ""
            progress = 0f
            transferSpeed = "0 KB/s"
            remainingTime = "--:--"
            transferredSize = ""
            status = "Preparando servidor local..."
            acquireWakeLock(context)
            // Primer plano desde el inicio (no solo al llegar el primer byte)
            // para mantenerse activo en segundo plano desde el primer momento.
            updateService(context, fileName, 0, 1, false, "0 KB/s", "--:--")

            var lastNotifyMs = System.currentTimeMillis()
            var lastNotifyBytes = 0L
            var cancelled = false

            try {
                val total = PkgInspector.resolveSize(context, uri)
                if (total <= 0) throw Exception("No se pudo leer el tamaño del PKG")

                // Validación local: un PKG de PS4 empieza por "\x7FCNT" y su
                // cabecera declara el tamaño total. Si no coincide, el RPI lo
                // rechazará con "Unexpected file size" (típico al enviar solo
                // una parte de un paquete dividido).
                val info = PkgInspector.inspect(context, uri, total)
                if (!info.magicOk) {
                    throw Exception(
                        "El archivo no es un PKG de PS4 válido. Si descargaste un " +
                            "paquete dividido, selecciona el PKG completo/mergeado, no una parte."
                    )
                }
                if (info.sizeMismatch) {
                    throw Exception(
                        "El tamaño no coincide: el archivo tiene ${formatSize(total)} " +
                            "pero el PKG declara ${formatSize(info.packageSize)}. " +
                            "Es una parte de un paquete dividido o está incompleto."
                    )
                }
                log("[*] PKG válido${if (info.contentId.isNotBlank()) " (${info.contentId})" else ""}" +
                    if (info.isPatch) " [parche]" else "")

                val localIp = PkgHttpServer.localIpAddress(ip)
                    ?: throw Exception("No se obtuvo la IP del teléfono (¿está en la misma WiFi?)")

                val server = PkgHttpServer(
                    onProgress = { sent, totalBytes ->
                        if (totalBytes > 0) {
                            progress = (sent.toFloat() / totalBytes).coerceIn(0f, 1f)
                        }
                        transferredSize = "${formatSize(sent)} / ${formatSize(totalBytes)}"

                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastNotifyMs) / 1000.0
                        if (elapsed >= 1.0) {
                            val bps = (sent - lastNotifyBytes) / elapsed
                            if (bps > 0) {
                                transferSpeed = formatSpeed(bps)
                                remainingTime = formatDuration(((totalBytes - sent) / bps).toLong())
                            }
                            lastNotifyMs = now
                            lastNotifyBytes = sent
                        }

                        if (installIndeterminate) installIndeterminate = false
                        updateService(
                            context, installFileName, (progress * 100).toInt(), 1,
                            false, transferSpeed, remainingTime
                        )
                    },
                    onComplete = {
                        installIndeterminate = true
                        status = "Instalando en la PS4..."
                        progress = 1f
                        transferSpeed = "0 KB/s"
                        remainingTime = "00:00"
                        log("[✔] PKG transferido al 100%, la PS4 lo está instalando...")
                    },
                    onClientConnected = {
                        installIndeterminate = false
                        status = "La PS4 está descargando el PKG..."
                        log("[*] La PS4 conectó al servidor local, descargando...")
                    }
                )

                if (!server.start(context, uri, total, fileName, installServerPort.toIntOrNull() ?: PkgHttpServer.DEFAULT_PORT)) {
                    throw Exception("No se pudo abrir el puerto ${installServerPort.ifBlank { PkgHttpServer.DEFAULT_PORT.toString() }}")
                }
                pkgServer = server
                installUrl = "http://$localIp:${server.boundPort}/pkg"
                log("[*] Servidor local listo: $installUrl")

                var response: String? = null
                var method = ""
                var etaHenDetail = ""
                var rpiDetail = ""
                var rpiReached = false

                // Detección del método disponible en la consola (igual que
                // DirectPackageInstaller): RPI expone GET /api devolviendo
                // "Unsupported method"+"fail"; etaHEN responde "etaHEN" en "/".
                status = "Detectando servicio en la PS4 (12800)..."
                val detected = detectConsoleMethod(ip)
                log("[*] Servicio detectado en 12800: ${detected ?: "ninguno"}")

                if (detected == "etaHEN") {
                    status = "Enviando orden a la PS4 (etaHEN)..."
                    try {
                        val result = postEtaHen(ip, installUrl)
                        response = result
                        method = "etaHEN"
                    } catch (e: Exception) {
                        etaHenDetail = e.message ?: "sin respuesta"
                        log("[!] etaHEN no respondió: $etaHenDetail")
                    }
                } else {
                    // "RPI" o desconocido: el PS4 con Remote Package Installer usa
                    // POST /api/install con {"type":"direct","packages":["..."]}.
                    status = "Enviando orden a la PS4 (Remote Package Installer)..."
                    try {
                        var result = postRpi(ip, installUrl)
                        rpiReached = true

                        // 0x80990015 = SCE_BGFT_ERROR_TASK_DUPLICATED: ya existe una
                        // tarea con ese CONTENT_ID. La borramos y reintentamos una vez.
                        if (parseRpiErrorCode(result) == RPI_ERR_TASK_DUPLICATED) {
                            log("[!] Tarea duplicada en la PS4 (0x80990015); limpiando...")
                            status = "Limpiando descarga anterior en la PS4..."
                            if (clearStaleRpiTask(ip, info.contentId, info.subType)) {
                                result = postRpi(ip, installUrl)
                            }
                        }

                        if (result.contains("\"success\"", ignoreCase = true) ||
                            result.contains("task_id")
                        ) {
                            response = result
                            method = "RPI"
                        } else {
                            rpiDetail = "respuesta inesperada: ${result.trim().take(300)}"
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: "sin respuesta"
                        // Si hubo respuesta HTTP "HTTP <code> ..." la consola sí
                        // contestó: /api/install es el endpoint de RPI, así que NO
                        // reintentamos con etaHEN (solo enmascararía el error real).
                        rpiReached = msg.startsWith("HTTP ")
                        rpiDetail = msg
                        log("[!] RPI no respondió: $rpiDetail")
                    }

                    // Fallback etaHEN solo si la consola no contestó por HTTP
                    // (puerto cerrado, IP inalcanzable, timeout...).
                    if (response == null && !rpiReached) {
                        status = "Enviando orden a la PS4 (etaHEN)..."
                        try {
                            val result = postEtaHen(ip, installUrl)
                            response = result
                            method = "etaHEN"
                        } catch (e: Exception) {
                            etaHenDetail = e.message ?: "sin respuesta"
                            log("[!] etaHEN no respondió: $etaHenDetail")
                        }
                    }
                }

                if (response == null) {
                    val rpiReason = extractApiError(rpiDetail)
                    val code = parseRpiErrorCode(rpiDetail)
                    val hint = rpiErrorHint(code)
                    val detail = when {
                        hint != null -> hint
                        rpiDetail.isNotBlank() -> rpiReason.ifBlank { rpiDetail }
                        etaHenDetail.isNotBlank() -> extractApiError(etaHenDetail).ifBlank { etaHenDetail }
                        else -> "sin respuesta de la consola"
                    }
                    throw Exception("La consola rechazó la instalación: $detail")
                }

                installMethod = method
                installIndeterminate = true
                status = "Esperando a que la PS4 descargue..."
                log("[✔] Orden aceptada por la PS4 vía $method")

                val waitingSince = System.currentTimeMillis()
                var lastProgressBytes = 0L
                var lastProgressAt = System.currentTimeMillis()

                // Sin límite de tiempo total: un PKG de varios GB (p. ej. 11.5 GB)
                // tarda más que cualquier tope fijo (antes eran 20 min) y se cortaba
                // a mitad. Solo fallamos si la descarga ya empezó y no avanza en
                // 3 minutos (conexión cortada) para poder reanudar con Range.
                while (isActive) {
                    if (server.isCompleted) break

                    val served = server.progressBytes
                    if (served > lastProgressBytes) {
                        lastProgressBytes = served
                        lastProgressAt = System.currentTimeMillis()
                    }

                    if (!server.clientConnected && System.currentTimeMillis() - waitingSince > 90_000) {
                        throw Exception("La PS4 no se conectó al servidor ($installUrl). ¿Están en la misma red?")
                    }

                    if (server.clientConnected && System.currentTimeMillis() - lastProgressAt > 180_000) {
                        throw Exception("La descarga desde la PS4 se detuvo (sin avance en 3 min). Pulsa Reintentar para reanudar.")
                    }

                    delay(250)
                }

                if (!isActive) throw CancellationException()

                delay(6000)

                status = "¡Instalación enviada! ✅"
                installIndeterminate = false
                progress = 1f
                log("[✔] $fileName instalado vía $method")
                playSound(context, R.raw.success)
                vibrate(context, success = true)
                emitEvent("¡PKG enviado a la PS4 para instalar!")

            } catch (e: Exception) {
                if (e is CancellationException) {
                    cancelled = true
                    status = "Instalación cancelada"
                } else {
                    status = "Error al instalar ❌"
                    installFailed = true
                    installError = e.message ?: "Error desconocido"
                    log("[ERROR] Instalación: ${e.message}")
                    playSound(context, R.raw.error)
                    vibrate(context, success = false)
                    emitEvent("Error al instalar: ${e.message}", true)
                }
            } finally {
                pkgServer?.stop()
                pkgServer = null
                releaseWakeLock()
                // Actualiza el aviso final según el resultado (y lo retira).
                val result = when {
                    installFailed -> "error"
                    cancelled -> "cancel"
                    else -> "done"
                }
                updateService(
                    context, fileName,
                    if (result == "done") 100 else (progress * 100).toInt().coerceIn(0, 100),
                    1, isDone = true, result = result
                )
                isInstalling = false
                installIndeterminate = false
                isSending = false
                installJob = null
            }
        }
    }

    private val RPI_ERR_TASK_DUPLICATED = 0x80990015.toInt()

    /** Lee el campo numérico "error_code" (hex o decimal) de una respuesta de RPI. */
    private fun parseRpiErrorCode(raw: String): Int? {
        val key = "error_code"
        val idx = raw.indexOf(key)
        if (idx < 0) return null
        val colon = raw.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < raw.length && raw[i].isWhitespace()) i++
        var hex = false
        if (i + 1 < raw.length && raw[i] == '0' && (raw[i + 1] == 'x' || raw[i + 1] == 'X')) {
            hex = true
            i += 2
        }
        val sb = StringBuilder()
        while (i < raw.length && raw[i].isLetterOrDigit()) {
            sb.append(raw[i])
            i++
        }
        if (sb.isEmpty()) return null
        return try {
            if (hex) sb.toString().toLong(16).toInt() else sb.toString().toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** Traduce los códigos de error más comunes del BGFT de la PS4. */
    private fun rpiErrorHint(code: Int?): String? = when (code) {
        RPI_ERR_TASK_DUPLICATED ->
            "tarea de descarga duplicada en la PS4 (0x80990015). Ya había una descarga " +
                "de este mismo contenido; se intentó eliminar automáticamente. " +
                "Reintenta o borra la descarga pendiente en la PS4 (Notificaciones/Descargas)."
        else -> null
    }

    /** Busca la tarea duplicada por CONTENT_ID y la elimina. Devuelve true si la borró. */
    private fun clearStaleRpiTask(ps4Ip: String, contentId: String, subType: Int): Boolean {
        if (contentId.isBlank()) return false
        val taskId = rpiFindTask(ps4Ip, contentId, subType) ?: return false
        log("[*] Tarea duplicada encontrada (task_id=$taskId); eliminando...")
        return rpiUnregisterTask(ps4Ip, taskId)
    }

    private fun rpiFindTask(ps4Ip: String, contentId: String, subType: Int): Int? {
        return try {
            val json = "{\"content_id\":\"${contentId.replace("\"", "")}\",\"sub_type\":$subType}"
            val r = httpPost(
                "http://$ps4Ip:12800/api/find_task",
                json.toByteArray(Charsets.UTF_8),
                "application/json"
            )
            parseJsonInt(r, "task_id")
        } catch (_: Exception) {
            null
        }
    }

    private fun rpiUnregisterTask(ps4Ip: String, taskId: Int): Boolean {
        return try {
            val json = "{\"task_id\":$taskId}"
            val r = httpPost(
                "http://$ps4Ip:12800/api/unregister_task",
                json.toByteArray(Charsets.UTF_8),
                "application/json"
            )
            r.contains("\"success\"", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseJsonInt(raw: String, key: String): Int? {
        val k = "\"$key\""
        val idx = raw.indexOf(k)
        if (idx < 0) return null
        val colon = raw.indexOf(':', idx + k.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < raw.length && raw[i].isWhitespace()) i++
        val sb = StringBuilder()
        if (i < raw.length && raw[i] == '-') {
            sb.append('-')
            i++
        }
        while (i < raw.length && raw[i].isDigit()) {
            sb.append(raw[i])
            i++
        }
        return sb.toString().toIntOrNull()
    }

    private fun postEtaHen(ps4Ip: String, url: String): String {
        // etaHEN DPI v2 (puerto 12800): POST /upload multipart/form-data con dos
        // campos, "file" (vacío, octet-stream) y "url" (la URL del pkg). Igual
        // que DirectPackageInstaller. Responde "SUCCESS: ..." / "FAILED: ...".
        try {
            val boundary = "----qsconnection" + System.nanoTime().toString(16)
            val body = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n\r\n")
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"url\"\r\n\r\n")
                append(url).append("\r\n")
                append("--").append(boundary).append("--\r\n")
            }.toByteArray(Charsets.UTF_8)

            val r = httpPost(
                "http://$ps4Ip:12800/upload",
                body,
                "multipart/form-data; boundary=$boundary"
            )
            if (r.contains("SUCCESS:", ignoreCase = true) ||
                r.contains("\"res\":\"0\"") ||
                r.contains("success", ignoreCase = true)
            ) return r
            throw Exception(r.trim().take(300).ifBlank { "respuesta vacía" })
        } catch (first: Exception) {
            // Compat: algunos DPI v2 alternativos aceptan el campo "url" en "/".
            try {
                val form = "url=" + java.net.URLEncoder.encode(url, "UTF-8")
                val r = httpPost(
                    "http://$ps4Ip:12800/",
                    form.toByteArray(Charsets.UTF_8),
                    "application/x-www-form-urlencoded"
                )
                if (r.contains("SUCCESS:", ignoreCase = true) ||
                    r.contains("\"res\":\"0\"") ||
                    r.contains("success", ignoreCase = true)
                ) return r
                throw Exception(r.trim().take(300).ifBlank { "respuesta vacía" })
            } catch (_: Exception) {
                throw first
            }
        }
    }

    private fun postRpi(ps4Ip: String, url: String): String {
        val escaped = java.net.URLEncoder.encode(url, "UTF-8")
        val json = "{\"type\":\"direct\",\"packages\":[\"$escaped\"]}"

        return httpPost(
            "http://$ps4Ip:12800/api/install",
            json.toByteArray(Charsets.UTF_8),
            "application/json",
            readTimeoutMs = 120_000
        )
    }

    /**
     * Detecta qué servicio de instalación escucha en el puerto 12800, igual que
     * DirectPackageInstaller:
     *  - RPI: GET /api -> JSON con "Unsupported method" (y "fail").
     *  - etaHEN: GET / -> contiene "etaHEN".
     * Devuelve "RPI", "etaHEN" o null si no responde ninguno.
     */
    private fun detectConsoleMethod(ps4Ip: String): String? {
        try {
            val r = httpGet("http://$ps4Ip:12800/api", 5000)
            if (r.contains("Unsupported method", ignoreCase = true) &&
                r.contains("fail", ignoreCase = true)
            ) {
                return "RPI"
            }
        } catch (_: Exception) {
        }
        try {
            val r = httpGet("http://$ps4Ip:12800/", 3000)
            if (r.contains("etaHEN", ignoreCase = true)) {
                return "etaHEN"
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun httpGet(target: String, readTimeoutMs: Int = 5000): String {
        val connection = java.net.URL(target).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "*/*")

            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(
        target: String,
        body: ByteArray,
        contentType: String,
        readTimeoutMs: Int = 20_000
    ): String {
        val connection = java.net.URL(target).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Accept", "*/*")
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..399) return text
            throw Exception("HTTP $code ${text.take(400)}")
        } finally {
            connection.disconnect()
        }
    }

    /** Extrae el motivo legible del cuerpo de error de RPI/etaHEN. */
    private fun extractApiError(raw: String): String {
        if (raw.isBlank()) return ""
        val key = "\"error\""
        val idx = raw.indexOf(key)
        if (idx >= 0) {
            val colon = raw.indexOf(':', idx + key.length)
            if (colon >= 0) {
                val start = raw.indexOf('"', colon + 1)
                val end = if (start >= 0) raw.indexOf('"', start + 1) else -1
                if (end > start) return raw.substring(start + 1, end).trim()
            }
        }
        val failed = raw.indexOf("FAILED:", ignoreCase = true)
        if (failed >= 0) return raw.substring(failed).trim().take(200)
        val http = raw.indexOf("HTTP ")
        if (http >= 0) return raw.substring(http).trim().take(200)
        return ""
    }

    private fun acquireWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "qsconnection:pkginstall").apply {
                setReferenceCounted(false)
                // 12 h: un PKG de 100 GB a velocidad lenta puede tardar horas;
                // el procesador no debe dormirse a mitad de la descarga.
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}

data class InstallTestResult(val message: String, val ok: Boolean, val port: String)

data class ScanResult(val ip: String, val openPorts: List<Int>)
