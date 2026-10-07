package com.nuvio.tv.core.profile.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarRasterPipelineTvTest {
    private fun encoded(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until 200) for (y in 0 until 100) bitmap.setPixel(x,y,if (x<100) Color.RED else Color.BLUE)
            return ByteArrayOutputStream().use { assertTrue(bitmap.compress(format,100,it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }

    @Test fun actualAndroidCropExportsTheSelectedRegionAndAllFourOptimizedSizes() {
        AvatarRasterPipeline.decode(encoded()).use { source ->
            assertEquals(200,source.width); assertEquals(100,source.height)
            val variants = AvatarRasterPipeline.variants(source,AvatarCrop(centerX=1f,zoom=2f))
            assertEquals(setOf(64,128,256,512),variants.keys)
            for ((size,bytes) in variants) {
                val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                try { assertEquals(size,bitmap.width);assertEquals(size,bitmap.height);assertEquals(Color.BLUE,bitmap.getPixel(size/2,size/2)) }
                finally { bitmap.recycle() }
            }
        }
    }

    @Test fun actualDecoderRejectsActiveVectorsAndOversizedHeadersBeforeAllocatingPixels() {
        try { AvatarRasterPipeline.decode("<svg onload='alert(1)'/>".toByteArray()); fail("SVG import accepted") }
        catch (failure: AvatarImageException) { assertEquals(AvatarImageFailure.UNSUPPORTED,failure.reason) }
        val bytes = encoded()
        ByteBuffer.wrap(bytes).putInt(16,8192).putInt(20,8192)
        ByteBuffer.wrap(bytes).putInt(29,CRC32().apply { update(bytes,12,17) }.value.toInt())
        try { AvatarRasterPipeline.decode(bytes);fail("Pixel bomb accepted") }
        catch (failure: AvatarImageException) { assertEquals(AvatarImageFailure.TOO_LARGE,failure.reason) }
    }

    @Test fun jpegExifOrientationIsAppliedAndOriginalMetadataIsNotExported() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG)
        val exif = byteArrayOf(69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0)
        val length = exif.size+2
        val segment = byteArrayOf(0xff.toByte(),0xe1.toByte(),(length shr 8).toByte(),length.toByte())
        AvatarRasterPipeline.decode(jpeg.copyOfRange(0,2)+segment+exif+jpeg.copyOfRange(2,jpeg.size)).use { source ->
            assertEquals(100,source.width);assertEquals(200,source.height)
            val png = AvatarRasterPipeline.preview(source,AvatarCrop())
            assertFalse(png.toString(Charsets.ISO_8859_1).contains("Exif"))
        }
    }
}
