package com.example.validation

import com.example.data.model.FlacValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Validates downloaded FLAC files locally:
 * - HTTP status code
 * - File size (> 0)
 * - .flac extension & "fLaC" magic bytes
 * - Basic STREAMINFO & VORBIS_COMMENT metadata blocks
 * - PICTURE block (type 6) presence for cover artwork
 * - Local SHA-256 file fingerprint (clearly marked as local fingerprint only, NOT trusted-source proof
 *   and NOT STREAMINFO MD5 audio verification).
 */
class FlacFileValidator {

    suspend fun validate(
        file: File,
        httpStatusCode: Int?,
        expectedExtension: String = "flac"
    ): FlacValidationResult = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val httpOk = httpStatusCode != null && httpStatusCode in 200..299
        val sizeBytes = if (file.exists()) file.length() else 0L
        val extOk = file.extension.equals(expectedExtension, ignoreCase = true)

        if (!httpOk) {
            notes.add("HTTP response bukan 2xx (${httpStatusCode ?: "tidak ada"}).")
        }
        if (sizeBytes <= 0L) {
            notes.add("File kosong atau tidak ditemukan.")
            return@withContext FlacValidationResult(
                httpStatusValid = httpOk,
                httpStatusCode = httpStatusCode,
                fileSizeBytes = sizeBytes,
                hasValidExtension = extOk,
                hasFlacSignature = false,
                streamInfoSummary = "Tidak tersedia",
                metadataSummary = "Tidak tersedia",
                hasCoverArtwork = false,
                coverArtworkSummary = "Tidak ditemukan",
                sha256Fingerprint = "-",
                isOverallValid = false,
                notes = notes
            )
        }

        val sha256 = computeSha256(file)
        var hasSignature = false
        var streamInfoSummary = "STREAMINFO tidak ditemukan"
        var metadataSummary = "Belum ada blok metadata Vorbis"
        var hasCoverArtwork = false
        var coverSummary = "Tidak ada blok PICTURE"

        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            val magicRead = input.read(magic)
            if (magicRead == 4 &&
                magic[0] == 'f'.code.toByte() &&
                magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() &&
                magic[3] == 'C'.code.toByte()
            ) {
                hasSignature = true
                var isLastBlock = false
                var blockSafetyCounter = 0

                while (!isLastBlock && blockSafetyCounter < 32) {
                    blockSafetyCounter++
                    val header = ByteArray(4)
                    if (input.read(header) != 4) break

                    val headerByte0 = header[0].toInt() and 0xFF
                    isLastBlock = (headerByte0 and 0x80) != 0
                    val blockType = headerByte0 and 0x7F
                    val blockLength = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

                    if (blockLength <= 0 || blockLength > 16 * 1024 * 1024) {
                        break
                    }

                    when (blockType) {
                        0 -> { // STREAMINFO
                            val payload = ByteArray(blockLength)
                            if (readExact(input, payload) == blockLength && blockLength >= 18) {
                                val sampleRate = ((payload[10].toInt() and 0xFF) shl 12) or
                                    ((payload[11].toInt() and 0xFF) shl 4) or
                                    ((payload[12].toInt() and 0xF0) ushr 4)
                                val channels = ((payload[12].toInt() and 0x0E) ushr 1) + 1
                                val bitsPerSample = (((payload[12].toInt() and 0x01) shl 4) or
                                    ((payload[13].toInt() and 0xF0) ushr 4)) + 1
                                val totalSamples = ((payload[13].toLong() and 0x0F) shl 32) or
                                    ((payload[14].toLong() and 0xFF) shl 24) or
                                    ((payload[15].toLong() and 0xFF) shl 16) or
                                    ((payload[16].toLong() and 0xFF) shl 8) or
                                    (payload[17].toLong() and 0xFF)
                                val durationSec = if (sampleRate > 0) totalSamples / sampleRate else 0L
                                streamInfoSummary = "$sampleRate Hz • $channels ch • $bitsPerSample-bit • ${durationSec}s"
                            } else {
                                break
                            }
                        }
                        4 -> { // VORBIS_COMMENT
                            val payload = ByteArray(blockLength)
                            if (readExact(input, payload) == blockLength && blockLength >= 8) {
                                val vendorLen = readLittleEndianInt(payload, 0)
                                val vendor = if (vendorLen in 1..256 && 4 + vendorLen <= payload.size) {
                                    String(payload, 4, vendorLen, Charsets.UTF_8)
                                } else {
                                    "Vorbis Comment"
                                }
                                metadataSummary = "VORBIS_COMMENT terdeteksi ($vendor)"
                            } else {
                                break
                            }
                        }
                        6 -> { // PICTURE
                            hasCoverArtwork = true
                            val payload = ByteArray(blockLength)
                            if (readExact(input, payload) == blockLength && blockLength >= 12) {
                                val mimeLen = readBigEndianInt(payload, 4)
                                val mime = if (mimeLen in 1..128 && 8 + mimeLen <= payload.size) {
                                    String(payload, 8, mimeLen, Charsets.US_ASCII)
                                } else {
                                    "image/*"
                                }
                                coverSummary = "Cover artwork terdeteksi ($mime, ${blockLength} B)"
                            } else {
                                coverSummary = "Blok PICTURE terdeteksi (${blockLength} B)"
                                break
                            }
                        }
                        else -> {
                            skipExact(input, blockLength.toLong())
                        }
                    }
                }
            } else {
                notes.add("Signature magic header 'fLaC' tidak ditemukan.")
            }
        }

        notes.add("SHA-256 dihitung sebagai fingerprint file lokal saja, bukan bukti sumber tepercaya dan bukan verifikasi STREAMINFO MD5 audio.")

        val valid = httpOk && sizeBytes > 0L && extOk && hasSignature
        FlacValidationResult(
            httpStatusValid = httpOk,
            httpStatusCode = httpStatusCode,
            fileSizeBytes = sizeBytes,
            hasValidExtension = extOk,
            hasFlacSignature = hasSignature,
            streamInfoSummary = streamInfoSummary,
            metadataSummary = metadataSummary,
            hasCoverArtwork = hasCoverArtwork,
            coverArtworkSummary = coverSummary,
            sha256Fingerprint = sha256,
            isOverallValid = valid,
            notes = notes
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readExact(input: FileInputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun skipExact(input: FileInputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val toRead = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
