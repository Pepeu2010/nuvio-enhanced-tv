package com.nuvio.tv.ui.theme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
private val staticFonts = listOf(
    Triple("regular", 400, "2602c71d1f36c96da0022706f15da7d51102d19d10510b77b5a39bf861122807"),
    Triple("medium", 500, "729037f5dc28daa949c5ce8e69d945bf14faf2649db737e6eb085c120c778dd0"),
    Triple("semibold", 600, "2b2ab8c077256d2849465199657f042e36588a7b88d345acd2ab4e5807ebef99"),
    Triple("bold", 700, "f9696798e2d82d52207ac00c4462bc86999d371ab87401db4067feb3def4d27b")
)

private fun tables(bytes: ByteArray): Map<String, Int> {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return (0 until (input.getShort(4).toInt() and 0xffff)).associate { index ->
        val entry = 12 + index * 16
        String(bytes, entry, 4, Charsets.US_ASCII) to input.getInt(entry + 8)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }


class TelumiaDisplayFontTest {
    @Test fun staticResourceFontsContainRealWeightsAndPinnedLicensedBytes() {
        for ((name, weight, hash) in staticFonts) {
            val bytes = File("src/main/res/font/manrope_$name.ttf").readBytes()
            val table = tables(bytes)
            assertFalse(name, "fvar" in table)
            assertEquals(name, weight, ByteBuffer.wrap(bytes).getShort(table.getValue("OS/2") + 4).toInt())
            assertEquals(name, hash, sha256(bytes))
        }
    }
}
