package com.example.qsconnection

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Lee la cabecera de un PKG de PS4 para validarlo antes de enviarlo al
 * Remote Package Installer.
 *
 * El RPI (pkg.c -> pkg_setup_prerequisites) comprueba:
 *  - El magic "\x7FCNT" en el offset 0x00.
 *  - Que el tamaño total servido coincida con el campo package_size (BE64 en
 *    0x430); si no, responde "Unexpected file size" (p. ej. al enviar solo una
 *    parte de un paquete dividido).
 *
 * Por eso conviene detectar ambos casos localmente y avisar al usuario con un
 * mensaje claro en vez de dejar que el RPI falle con un HTTP 500.
 */
object PkgInspector {

    private const val HEADER_SIZE = 0x2000
    private val MAGIC = byteArrayOf(0x7F, 'C'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte())

    private const val OFF_ENTRY_COUNT = 0x10
    private const val OFF_ENTRY_TABLE = 0x18
    private const val OFF_CONTENT_ID = 0x40
    private const val OFF_CONTENT_TYPE = 0x74
    private const val OFF_CONTENT_FLAGS = 0x78
    private const val OFF_PACKAGE_SIZE = 0x430

    // enum pkg_content_type
    private const val CONTENT_TYPE_AC = 0x1B
    private const val CONTENT_TYPE_AL = 0x1C

    // PKG_CONTENT_FLAGS_* (big-endian u32 en 0x78)
    private const val FLAG_FIRST_PATCH = 0x00100000
    private const val FLAG_SUBSEQUENT_PATCH = 0x40000000
    private const val FLAG_DELTA_PATCH = 0x41000000
    private const val FLAG_CUMULATIVE_PATCH = 0x60000000

    data class Info(
        val magicOk: Boolean,
        val actualSize: Long,
        val packageSize: Long,
        val entryCount: Int,
        val contentId: String,
        val contentType: Int,
        val isPatch: Boolean
    ) {
        val sizeMismatch: Boolean
            get() = magicOk && packageSize > 0L && actualSize > 0L && packageSize != actualSize

        /** SCE_BGFT_TASK_SUB_TYPE: Game=6, AC=7, Patch=8, License=9. */
        val subType: Int
            get() = when {
                isPatch -> 8
                contentType == CONTENT_TYPE_AC || contentType == CONTENT_TYPE_AL -> 7
                else -> 6
            }
    }

    /** Tamaño real del archivo (prefiere OpenableColumns.SIZE, luego el descriptor). */
    fun resolveSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    val size = cursor.getLong(idx)
                    if (size > 0L) return size
                }
            }
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length > 0L) return fd.length
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    fun inspect(context: Context, uri: Uri, actualSize: Long): Info {
        val header = readHeader(context, uri)
            ?: return Info(false, actualSize, 0L, 0, "", 0, false)

        val magicOk = header.size >= 4 && (0 until 4).all { header[it] == MAGIC[it] }
        if (!magicOk) {
            return Info(false, actualSize, 0L, 0, "", 0, false)
        }

        val packageSize = be64(header, OFF_PACKAGE_SIZE)
        val entryCount = be32(header, OFF_ENTRY_COUNT)
        val contentType = be32(header, OFF_CONTENT_TYPE)
        val flags = be32(header, OFF_CONTENT_FLAGS)
        val contentId = readCString(header, OFF_CONTENT_ID, 0x24)
        val isPatch = (flags and FLAG_FIRST_PATCH) != 0 ||
            (flags and FLAG_SUBSEQUENT_PATCH) != 0 ||
            (flags and FLAG_DELTA_PATCH) == FLAG_DELTA_PATCH ||
            (flags and FLAG_CUMULATIVE_PATCH) == FLAG_CUMULATIVE_PATCH

        return Info(true, actualSize, packageSize, entryCount, contentId, contentType, isPatch)
    }

    private fun readHeader(context: Context, uri: Uri): ByteArray? {
        var stream: InputStream? = null
        return try {
            stream = context.contentResolver.openInputStream(uri) ?: return null
            val buffer = ByteArray(HEADER_SIZE)
            var read = 0
            while (read < HEADER_SIZE) {
                val n = stream.read(buffer, read, HEADER_SIZE - read)
                if (n <= 0) break
                read += n
            }
            if (read < 4) null else buffer.copyOf(read)
        } catch (_: Exception) {
            null
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun be32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun be64(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readCString(data: ByteArray, offset: Int, max: Int): String {
        if (offset >= data.size) return ""
        val end = minOf(offset + max, data.size)
        val bytes = ArrayList<Byte>(max)
        for (i in offset until end) {
            val b = data[i]
            if (b.toInt() == 0) break
            bytes.add(b)
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII).trim()
    }
}