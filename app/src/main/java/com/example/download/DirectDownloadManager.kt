package com.example.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.core.config.TestMode
import com.example.core.security.LogSanitizer
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator
import com.example.data.model.DownloadProgressState
import com.example.data.model.DownloadStage
import com.example.validation.FlacFileValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Handles Data Plane direct audio downloads directly from the Lucida Worker URL via OkHttp.
 * VPS is never used as a file-transfer proxy.
 * Staging file lives inside app cache; exports cleanly via MediaStore (API 29+) or SAF.
 */
class DirectDownloadManager(
    private val context: Context,
    private val validator: FlacFileValidator = FlacFileValidator(),
    private val okHttpClient: OkHttpClient = defaultDownloadClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeCall: Call? = null

    private val _state = MutableStateFlow(DownloadProgressState())
    val state: StateFlow<DownloadProgressState> = _state.asStateFlow()

    fun startDirectDownload(
        mode: TestMode,
        downloadUrl: String,
        trackTitle: String,
        trackArtist: String,
        workerServer: String,
        suggestedFileName: String? = null,
        onFinished: (success: Boolean) -> Unit = {}
    ) {
        cancelDownloadInternal(markCancelled = false)

        val safeName = sanitizeFileName(
            suggestedFileName ?: "${trackArtist}_${trackTitle}".ifBlank { "lucida_track" }
        )

        _state.value = DownloadProgressState(
            trackTitle = trackTitle,
            trackArtist = trackArtist,
            workerServer = workerServer,
            stage = DownloadStage.CONNECTING,
            suggestedFileName = safeName
        )

        activeJob = scope.launch {
            val startTimeMs = System.currentTimeMillis()
            val stagingDir = File(context.cacheDir, "direct_downloads").apply { mkdirs() }
            val stagingFile = File(stagingDir, safeName)
            if (stagingFile.exists()) stagingFile.delete()

            try {
                val httpStatus: Int? = if (mode == TestMode.DEMO) {
                    runSimulatedWorkerStream(stagingFile, startTimeMs)
                } else {
                    runLiveWorkerStream(downloadUrl, stagingFile, startTimeMs)
                }

                if (!isActive) return@launch

                _state.update {
                    it.copy(
                        stage = DownloadStage.VALIDATING,
                        elapsedMillis = System.currentTimeMillis() - startTimeMs,
                        localStagingPath = stagingFile.absolutePath
                    )
                }

                val validation = validator.validate(
                    file = stagingFile,
                    httpStatusCode = httpStatus
                )

                val exportedNote = if (validation.isOverallValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStoreDownloads(stagingFile, safeName)
                } else {
                    null
                }

                val success = validation.isOverallValid
                _state.update {
                    it.copy(
                        stage = if (success) DownloadStage.COMPLETED else DownloadStage.ERROR,
                        validationResult = validation,
                        elapsedMillis = System.currentTimeMillis() - startTimeMs,
                        errorMessage = if (success) null else validation.notes.firstOrNull(),
                        exportedLocationNote = exportedNote
                    )
                }
                onFinished(success)
            } catch (_: CancellationException) {
                if (stagingFile.exists()) stagingFile.delete()
                _state.update {
                    it.copy(
                        stage = DownloadStage.CANCELLED,
                        speedBytesPerSec = 0L,
                        elapsedMillis = System.currentTimeMillis() - startTimeMs,
                        errorMessage = "Unduhan dibatalkan oleh pengguna."
                    )
                }
            } catch (e: Exception) {
                if (stagingFile.exists()) stagingFile.delete()
                _state.update {
                    it.copy(
                        stage = DownloadStage.ERROR,
                        speedBytesPerSec = 0L,
                        elapsedMillis = System.currentTimeMillis() - startTimeMs,
                        errorMessage = LogSanitizer.redactSensitive(
                            e.localizedMessage ?: "Gagal mengunduh langsung dari worker."
                        )
                    )
                }
                onFinished(false)
            }
        }
    }

    fun cancelDownload() {
        cancelDownloadInternal(markCancelled = true)
    }

    fun exportToSafUri(targetUri: Uri): Result<String> {
        val stagingPath = _state.value.localStagingPath
            ?: return Result.failure(IllegalStateException("Belum ada file terunduh untuk disimpan."))
        val source = File(stagingPath)
        if (!source.exists()) {
            return Result.failure(IllegalStateException("File staging tidak ditemukan."))
        }
        return try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                FileInputStream(source).use { input ->
                    input.copyTo(out)
                }
            } ?: return Result.failure(IllegalStateException("Gagal membuka lokasi tujuan SAF."))
            val note = "File disimpan via Storage Access Framework."
            _state.update { it.copy(exportedLocationNote = note) }
            Result.success(note)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun resetState() {
        cancelDownloadInternal(markCancelled = false)
        _state.value = DownloadProgressState()
    }

    private fun cancelDownloadInternal(markCancelled: Boolean) {
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeJob = null
        if (markCancelled) {
            _state.update {
                if (it.stage == DownloadStage.CONNECTING || it.stage == DownloadStage.DOWNLOADING) {
                    it.copy(
                        stage = DownloadStage.CANCELLED,
                        speedBytesPerSec = 0L,
                        errorMessage = "Unduhan dibatalkan oleh pengguna."
                    )
                } else {
                    it
                }
            }
        }
    }

    private suspend fun runLiveWorkerStream(
        downloadUrl: String,
        targetFile: File,
        startTimeMs: Long
    ): Int {
        when (val check = UrlValidator.validateWorkerHttpsUrl(downloadUrl)) {
            is UrlValidationResult.Invalid -> throw IllegalArgumentException(check.reason)
            is UrlValidationResult.Valid -> Unit
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .build()

        val call = okHttpClient.newCall(request)
        activeCall = call

        call.execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                throw IllegalStateException("Worker mengembalikan HTTP $code ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Response body kosong dari worker.")
            val totalLength = body.contentLength().takeIf { it > 0L }

            _state.update {
                it.copy(
                    stage = DownloadStage.DOWNLOADING,
                    httpStatusCode = code,
                    totalBytes = totalLength
                )
            }

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastReportTime = System.currentTimeMillis()
                    var bytesSinceLastReport = 0L

                    while (true) {
                        if (activeJob?.isActive != true) throw CancellationException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceLastReport += read

                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastReportTime
                        if (deltaMs >= 200L) {
                            val speed = (bytesSinceLastReport * 1000L) / deltaMs.coerceAtLeast(1L)
                            _state.update {
                                it.copy(
                                    bytesDownloaded = downloaded,
                                    speedBytesPerSec = speed,
                                    elapsedMillis = now - startTimeMs
                                )
                            }
                            lastReportTime = now
                            bytesSinceLastReport = 0L
                        }
                    }
                    output.flush()
                    _state.update {
                        it.copy(
                            bytesDownloaded = downloaded,
                            totalBytes = totalLength ?: downloaded,
                            elapsedMillis = System.currentTimeMillis() - startTimeMs
                        )
                    }
                }
            }
            return code
        }
    }

    private suspend fun runSimulatedWorkerStream(
        targetFile: File,
        startTimeMs: Long
    ): Int {
        val sampleFlacBytes = buildValidDemoFlacBytes()
        val totalSize = sampleFlacBytes.size.toLong()

        delay(220)
        _state.update {
            it.copy(
                stage = DownloadStage.DOWNLOADING,
                httpStatusCode = 200,
                totalBytes = totalSize
            )
        }

        val chunkSize = 16_384
        var offset = 0
        FileOutputStream(targetFile).use { output ->
            while (offset < sampleFlacBytes.size) {
                if (activeJob?.isActive != true) throw CancellationException()
                val count = minOf(chunkSize, sampleFlacBytes.size - offset)
                output.write(sampleFlacBytes, offset, count)
                offset += count
                delay(75)
                val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                val speed = (offset.toLong() * 1000L) / elapsed
                _state.update {
                    it.copy(
                        bytesDownloaded = offset.toLong(),
                        speedBytesPerSec = speed,
                        elapsedMillis = elapsed
                    )
                }
            }
            output.flush()
        }
        return 200
    }

    private fun saveToMediaStoreDownloads(source: File, fileName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/flac")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FLACDL_Tester")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Tersimpan di Downloads/FLACDL_Tester/$fileName"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates a valid minimal FLAC byte payload (fLaC magic + STREAMINFO + VORBIS_COMMENT + PICTURE + frame bytes)
     * so Demo Mode runs through the real FlacFileValidator parser end-to-end.
     */
    private fun buildValidDemoFlacBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        // 1. Magic "fLaC"
        out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))

        // 2. STREAMINFO (type = 0, not last, length = 34)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x22))
        val streamInfo = ByteArray(34)
        // min/max block size = 4096 (0x1000)
        streamInfo[0] = 0x10; streamInfo[1] = 0x00
        streamInfo[2] = 0x10; streamInfo[3] = 0x00
        // sampleRate = 44100 (0x0AC44) in 20 bits at bytes 10..12
        // channels = 2 (stored as 1) -> bits 1..3 of byte 12
        // bitsPerSample = 16 (stored as 15 = 0x0F) -> bit 0 of byte 12 + high nibble of byte 13
        // totalSamples = 11_377_800 (~258s)
        streamInfo[10] = 0x0A
        streamInfo[11] = 0xC4.toByte()
        streamInfo[12] = 0x42 // 0x4 (low sampleRate nibble) | 0x2 (channels=2 -> (1<<1)) | 0x0 (bps MSB)
        streamInfo[13] = 0xF0.toByte() // bps low 4 bits = 0xF -> 16-bit
        streamInfo[14] = 0x00
        streamInfo[15] = 0xAD.toByte()
        streamInfo[16] = 0x9C.toByte()
        streamInfo[17] = 0x08
        out.write(streamInfo)

        // 3. VORBIS_COMMENT (type = 4, not last)
        val vendorBytes = "FLACDL Demo Encoder".toByteArray(Charsets.UTF_8)
        val vorbisLen = 4 + vendorBytes.size + 4
        out.write(
            byteArrayOf(
                0x04,
                ((vorbisLen ushr 16) and 0xFF).toByte(),
                ((vorbisLen ushr 8) and 0xFF).toByte(),
                (vorbisLen and 0xFF).toByte()
            )
        )
        out.write(
            byteArrayOf(
                (vendorBytes.size and 0xFF).toByte(),
                ((vendorBytes.size ushr 8) and 0xFF).toByte(),
                ((vendorBytes.size ushr 16) and 0xFF).toByte(),
                ((vendorBytes.size ushr 24) and 0xFF).toByte()
            )
        )
        out.write(vendorBytes)
        out.write(byteArrayOf(0, 0, 0, 0)) // user comment count = 0

        // 4. PICTURE (type = 6, last metadata block -> 0x86)
        val mimeBytes = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val pictureLen = 4 + 4 + mimeBytes.size + 4 + 16 + 4 + 16
        out.write(
            byteArrayOf(
                0x86.toByte(),
                ((pictureLen ushr 16) and 0xFF).toByte(),
                ((pictureLen ushr 8) and 0xFF).toByte(),
                (pictureLen and 0xFF).toByte()
            )
        )
        out.write(byteArrayOf(0, 0, 0, 3)) // Front Cover
        out.write(byteArrayOf(0, 0, 0, mimeBytes.size.toByte()))
        out.write(mimeBytes)
        out.write(ByteArray(4 + 16 + 4 + 16))

        // 5. Simulated audio payload (~320 KB for smooth progress bar demo)
        val frameBlock = ByteArray(320 * 1024) { index -> (index % 251).toByte() }
        out.write(frameBlock)

        return out.toByteArray()
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "lucida_track" }
        return if (cleaned.endsWith(".flac", ignoreCase = true)) cleaned else "$cleaned.flac"
    }

    companion object {
        fun defaultDownloadClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
