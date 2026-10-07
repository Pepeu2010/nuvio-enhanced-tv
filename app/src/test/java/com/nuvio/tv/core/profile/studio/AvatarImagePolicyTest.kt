package com.nuvio.tv.core.profile.studio

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class AvatarImagePolicyTest {
    @Test fun invalidDimensionsAndDecodedPixelBombsAreRefused() {
        for ((width, height) in listOf(0 to 100, 100 to -1, 8193 to 1, 8192 to 8192, Int.MAX_VALUE to Int.MAX_VALUE)) {
            try { AvatarImagePolicy.checkDimensions(width, height); fail("Invalid raster accepted") }
            catch (_: AvatarImageException) { }
        }
        AvatarImagePolicy.checkDimensions(4096, 4096)
    }

    @Test fun encodedStreamCannotAllocateBeyondItsBoundOrAcceptAnEmptyDocument() {
        var supplied = 0
        val stream = object : InputStream() {
            override fun read(): Int { supplied++; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { supplied += length; return length }
        }
        try { AvatarImagePolicy.readBounded(stream); fail("Oversized stream accepted") }
        catch (failure: AvatarImageException) { assertEquals(AvatarImageFailure.TOO_LARGE, failure.reason) }
        assertEquals(AvatarImagePolicy.MAX_INPUT_BYTES + 1, supplied)
        try { AvatarImagePolicy.readBounded(ByteArrayInputStream(byteArrayOf())); fail("Empty image accepted") }
        catch (failure: AvatarImageException) { assertEquals(AvatarImageFailure.EMPTY, failure.reason) }
    }

    @Test fun streamsWithZeroProgressDoNotLoopForever() {
        var calls = 0
        val stream = object : InputStream() {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun read() = if (calls++ == 0) 7 else -1
        }
        assertArrayEquals(byteArrayOf(7), AvatarImagePolicy.readBounded(stream))
    }

    @Test fun cropRejectsNonFiniteValuesAndClampsNormalInput() {
        assertEquals(AvatarCrop(0f,1f,8f), AvatarCrop(-1f,2f,100f).validated())
        for (crop in listOf(AvatarCrop(zoom=Float.NaN),AvatarCrop(centerX=Float.POSITIVE_INFINITY),AvatarCrop(centerY=Float.NEGATIVE_INFINITY))) {
            try { crop.validated(); fail("Non-finite crop accepted") } catch (_: AvatarImageException) { }
        }
        assertEquals(listOf(64,128,256,512), AvatarImagePolicy.variantSizes)
        assertFalse("image/svg+xml" in AvatarImagePolicy.rasterMimeTypes)
    }
}
