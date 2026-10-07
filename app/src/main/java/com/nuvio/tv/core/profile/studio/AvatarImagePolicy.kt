package com.nuvio.tv.core.profile.studio

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal enum class AvatarImageFailure { EMPTY, UNSUPPORTED, TOO_LARGE, INVALID }
internal class AvatarImageException(val reason: AvatarImageFailure) : Exception(reason.name)
internal data class AvatarCrop(val centerX: Float = .5f, val centerY: Float = .5f, val zoom: Float = 1f) {
    fun validated(): AvatarCrop {
        if (!centerX.isFinite() || !centerY.isFinite() || !zoom.isFinite()) throw AvatarImageException(AvatarImageFailure.INVALID)
        return copy(centerX = centerX.coerceIn(0f, 1f), centerY = centerY.coerceIn(0f, 1f), zoom = zoom.coerceIn(1f, 8f))
    }
}

/** Pure bounds policy; no decoder or untrusted path is reached before these checks. */
internal object AvatarImagePolicy {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    const val MAX_PIXELS = 16L * 1024 * 1024
    val variantSizes = listOf(64, 128, 256, 512)
    val rasterMimeTypes = setOf("image/png", "image/jpeg", "image/gif", "image/bmp", "image/x-ms-bmp", "image/webp")

    fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw AvatarImageException(AvatarImageFailure.INVALID)
        if (width > 8192 || height > 8192 || width.toLong() * height > MAX_PIXELS)
            throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
    }

    fun checkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) throw AvatarImageException(AvatarImageFailure.EMPTY)
        if (bytes.size > MAX_INPUT_BYTES) throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
    }

    fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val count = stream.read(chunk, 0, minOf(chunk.size, MAX_INPUT_BYTES + 1 - total))
            if (count < 0) break
            if (count == 0) {
                val single = stream.read()
                if (single < 0) break
                output.write(single); total++
            } else { output.write(chunk, 0, count); total += count }
            if (total > MAX_INPUT_BYTES) throw AvatarImageException(AvatarImageFailure.TOO_LARGE)
        }
        return output.toByteArray().also(::checkBytes)
    }
}
