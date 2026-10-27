package dev.etran.slimetoanvil.slime

import io.airlift.compress.zstd.ZstdDecompressor
import java.nio.ByteBuffer

internal object Zstd {
    private const val MAX_SIZE = 1 shl 29 // 512 MiB, protects against corrupt size fields

    fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray {
        if (uncompressedSize < 0 || uncompressedSize > MAX_SIZE) {
            throw SlimeFormatException("Implausible uncompressed size: $uncompressedSize")
        }
        val out = ByteArray(uncompressedSize)
        val written = try {
            ZstdDecompressor().decompress(compressed, 0, compressed.size, out, 0, out.size)
        } catch (e: RuntimeException) {
            throw SlimeFormatException("zstd decompression failed: ${e.message}", e)
        }
        if (written != uncompressedSize) {
            throw SlimeFormatException("zstd size mismatch (expected $uncompressedSize, got $written)")
        }
        return out
    }

    /** Reads `int compressedSize, int uncompressedSize, byte[compressedSize]` and returns the inflated bytes. */
    fun readBlock(buf: ByteBuffer): ByteArray {
        val compressedSize = buf.int
        val uncompressedSize = buf.int
        if (compressedSize < 0 || compressedSize > buf.remaining()) {
            throw SlimeFormatException("Compressed block ($compressedSize bytes) exceeds file size")
        }
        val compressed = ByteArray(compressedSize)
        buf.get(compressed)
        return decompress(compressed, uncompressedSize)
    }
}
