package com.nuvio.tv.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDisplayFontTvTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fonts = listOf(
        Triple(R.font.manrope_regular, 400, "2602c71d1f36c96da0022706f15da7d51102d19d10510b77b5a39bf861122807"),
        Triple(R.font.manrope_medium, 500, "729037f5dc28daa949c5ce8e69d945bf14faf2649db737e6eb085c120c778dd0"),
        Triple(R.font.manrope_semibold, 600, "2b2ab8c077256d2849465199657f042e36588a7b88d345acd2ab4e5807ebef99"),
        Triple(R.font.manrope_bold, 700, "f9696798e2d82d52207ac00c4462bc86999d371ab87401db4067feb3def4d27b")
    )

    @Test fun actualApkContainsTheFourStaticWeightResources() {
        for ((id, weight, hash) in fonts) {
            val bytes = context.resources.openRawResource(id).use { it.readBytes() }
            val input = ByteBuffer.wrap(bytes)
            val tables = (0 until (input.getShort(4).toInt() and 0xffff)).associate { index ->
                val entry = 12 + index * 16
                String(bytes, entry, 4, Charsets.US_ASCII) to input.getInt(entry + 8)
            }
            assertFalse("fvar" in tables)
            assertEquals(weight, input.getShort(tables.getValue("OS/2") + 4).toInt())
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
    }

    @Test fun nativeFontLoaderDrawsHeavierBoldWithoutSyntheticPaint() {
        fun ink(id: Int): Int {
            val typeface = ResourcesCompat.getFont(context, id)
            assertNotNull(typeface)
            val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 72f
                    this.typeface = typeface
                    isFakeBoldText = false
                }
                Canvas(bitmap).drawText("Áudio • Iludida", 8f, 90f, paint)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count { Color.alpha(it) > 32 }
            } finally { bitmap.recycle() }
        }
        val regular = ink(R.font.manrope_regular)
        val bold = ink(R.font.manrope_bold)
        assertTrue("Regular font rendered no letters", regular > 500)
        assertTrue("Native bold weight must visibly exceed regular: $regular / $bold", bold > regular * 1.1)
    }
}
