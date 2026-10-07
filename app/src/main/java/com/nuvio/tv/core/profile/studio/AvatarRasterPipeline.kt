package com.nuvio.tv.core.profile.studio

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal class AvatarRasterSource internal constructor(internal val bitmap: Bitmap) : AutoCloseable {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
    override fun close() = synchronized(this) { if (!bitmap.isRecycled) bitmap.recycle() }
}

/** Bounded Android raster import for the existing profile editor. */
internal object AvatarRasterPipeline {
    fun readContent(resolver: ContentResolver, uri: Uri): AvatarRasterSource {
        // Only explicit document/clipboard grants. No arbitrary filesystem or network URL import.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank())
            throw AvatarImageException(AvatarImageFailure.UNSUPPORTED)
        val bytes = resolver.openInputStream(uri)?.use(AvatarImagePolicy::readBounded)
            ?: throw AvatarImageException(AvatarImageFailure.EMPTY)
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): AvatarRasterSource {
        AvatarImagePolicy.checkBytes(bytes)
        var decoded: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType !in AvatarImagePolicy.rasterMimeTypes)
                throw AvatarImageException(AvatarImageFailure.UNSUPPORTED)
            AvatarImagePolicy.checkDimensions(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inSampleSize = AvatarImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            }
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw AvatarImageException(AvatarImageFailure.INVALID)
            AvatarImagePolicy.checkDimensions(decoded.width, decoded.height)
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val result = orient(decoded, orientation)
            if (result !== decoded) decoded.recycle()
            decoded = null
            return AvatarRasterSource(result)
        } catch (failure: AvatarImageException) { throw failure }
        catch (_: Exception) { throw AvatarImageException(AvatarImageFailure.INVALID) }
        finally { decoded?.recycle() }
    }

    fun preview(source: AvatarRasterSource, crop: AvatarCrop): ByteArray = render(source, crop, 256)

    fun variants(source: AvatarRasterSource, crop: AvatarCrop): Map<Int, ByteArray> =
        AvatarImagePolicy.variantSizes.associateWith { render(source, crop, it) }

    private fun render(source: AvatarRasterSource, crop: AvatarCrop, size: Int): ByteArray = synchronized(source) {
        if (source.bitmap.isRecycled) throw AvatarImageException(AvatarImageFailure.INVALID)
        val normalized = crop.validated()
        val side = (minOf(source.width, source.height) / normalized.zoom).roundToInt().coerceAtLeast(1)
        val x = (normalized.centerX * source.width - side / 2f).roundToInt().coerceIn(0, source.width - side)
        val y = (normalized.centerY * source.height - side / 2f).roundToInt().coerceIn(0, source.height - side)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).drawBitmap(source.bitmap, Rect(x, y, x + side, y + side), Rect(0, 0, size, size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            ByteArrayOutputStream().use { stream ->
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw AvatarImageException(AvatarImageFailure.INVALID)
                stream.toByteArray()
            }
        } finally { output.recycle() }
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
