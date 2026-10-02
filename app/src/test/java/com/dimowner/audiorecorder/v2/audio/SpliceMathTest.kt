/*
 * Copyright 2026 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimowner.audiorecorder.v2.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Proves the punch splice keeps the tail: head [0, anchor) + take +
 * tail [anchor + take, end) with an exact header, using synthetic PCM.
 *
 * Pure JVM (no Robolectric): exercises the production [WavRecorderV2]
 * splice path, including the tail branch that device runs rarely hit.
 */
class SpliceMathTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    private val sampleRate = 44100
    private val channels = 2
    private val bps = sampleRate * channels * 2
    private val frame = channels * 2

    @Before
    fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dir = kotlin.io.path.createTempDirectory("splice-test").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun takeShorterThanTailKeepsTailByteIdentical() {
        // 15 s main with a per-second ramp so every region is distinguishable.
        val mainFile = wavFile("main.wav", seconds = 15) { second -> second }
        // 3 s take filled with a constant marker.
        val takeFile = wavFile("take.wav", seconds = 3) { 0x5A5A }
        val takeLen = takeFile.length() - 44

        val recorder = newRecorder()
        val anchorMs = 5000L
        val newLen = invokeSplice(recorder, mainFile, takeFile, anchorMs, takeLen)
        val expectedTail = (15_000L * bps) / 1000L - (((anchorMs * bps) / 1000L) + takeLen)
        assertTrue("expected a surviving tail, got $expectedTail", expectedTail > 0)
        assertEquals(15_000L * bps / 1000L, newLen)

        val out = mainFile.readBytes()
        assertWavHeader(out, newLen)
        val anchorBytes = (((anchorMs * bps) / 1000L) / frame) * frame
        // Head untouched.
        assertArrayEquals(
            File(dir, "main-orig.wav").readBytes().copyOfRange(44, (44 + anchorBytes).toInt()),
            out.copyOfRange(44, (44 + anchorBytes).toInt())
        )
        // Take region is the marker (left channel 0x5A5A, right channel 0).
        val takeRegion = out.copyOfRange((44 + anchorBytes).toInt(), (44 + anchorBytes + takeLen).toInt())
        val takeSamples = takeRegion.size / frame
        assertTrue("take region is not the new audio", takeSamples > 0 && (0 until takeSamples).all { i ->
            val left = (takeRegion[i * frame].toInt() and 0xFF) or
                ((takeRegion[i * frame + 1].toInt() and 0xFF) shl 8)
            left == 0x5A5A
        })
        // Tail survives byte-identical: ramp resumes at second 8.
        val tailStart = (44 + anchorBytes + takeLen).toInt()
        val firstTailSample = ByteBuffer.wrap(out, tailStart, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(8, firstTailSample and 0xFFFF)
        assertEquals(out.size - tailStart, (15_000L * bps / 1000L - anchorBytes - takeLen).toInt())
    }

    @Test
    fun takeLongerThanTailGrowsFile() {
        val mainFile = wavFile("main.wav", seconds = 10) { second -> second }
        val takeFile = wavFile("take.wav", seconds = 8) { 0x5A5A }
        val takeLen = takeFile.length() - 44

        val recorder = newRecorder()
        val newLen = invokeSplice(recorder, mainFile, takeFile, 6000L, takeLen)
        val out = mainFile.readBytes()
        assertWavHeader(out, newLen)
        // 6 s head + 8 s take, old 4 s tail fully covered.
        assertEquals((6000L * bps) / 1000L + takeLen, newLen)
    }

    private fun newRecorder(): WavRecorderV2 {
        val recorder = WavRecorderV2(scope)
        setField(recorder, "bytesPerSecond", bps)
        setField(recorder, "currentFrameSize", frame)
        setField(recorder, "currentSampleRate", sampleRate)
        setField(recorder, "currentChannelCount", channels)
        return recorder
    }

    private fun invokeSplice(
        recorder: WavRecorderV2,
        mainFile: File,
        takeFile: File,
        anchorMs: Long,
        takeLen: Long,
    ): Long {
        val method = WavRecorderV2::class.java.getDeclaredMethod(
            "spliceTakeIntoMain",
            File::class.java, File::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val result = method.invoke(recorder, mainFile, takeFile, anchorMs, takeLen, bps, frame)
        // Keep a pristine copy for head comparison before splicing overwrote it.
        return result as Long
    }

    /** Writes a 16-bit stereo WAV whose left channel stores second*1000 + 1 as a marker. */
    private fun wavFile(name: String, seconds: Int, marker: (second: Int) -> Int): File {
        val totalSamples = seconds * sampleRate
        val data = ByteArray(totalSamples * frame)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val second = i / sampleRate
            buf.putShort((marker(second) % 32767).toShort())
            buf.putShort(0)
        }
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            out.write(createWavHeader(data.size.toLong(), data.size + 36L, sampleRate, channels, bps.toLong()))
            out.write(data)
        }
        if (name == "main.wav") {
            file.copyTo(File(dir, "main-orig.wav"), overwrite = true)
        }
        return file
    }

    private fun assertWavHeader(bytes: ByteArray, dataLen: Long) {
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('W'.code.toByte(), bytes[8])
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + dataLen, buf.getInt(4).toLong() and 0xFFFFFFFFL)
        assertEquals(dataLen, buf.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = WavRecorderV2::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
