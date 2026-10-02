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

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dimowner.audiorecorder.audio.AudioDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the punch-in re-record path: a take captured from an anchor splices
 * into the main file as head + take + tail, the tail surviving when the take
 * ends before the original end.
 *
 * Runs on a device: install both APKs with adb and run with `am instrument`,
 * since Gradle's installer is gated by MIUI's USB-install dialog.
 */
@RunWith(AndroidJUnit4::class)
class PunchSpliceInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var recorder: WavRecorderV2
    private lateinit var mainFile: File
    private lateinit var takeFile: File
    private lateinit var events: CopyOnWriteArrayList<RecorderEvent>

    private var startedLatch = CountDownLatch(1)
    private var finishedLatch = CountDownLatch(1)

    @Before
    fun setup() {
        assumeTrue("RECORD_AUDIO is not granted", grantMicPermission())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        recorder = WavRecorderV2(scope)
        val dir = File(context.cacheDir, "punch-test").apply { mkdirs() }
        mainFile = File(dir, "punch-main.wav").apply {
            delete()
            createNewFile()
        }
        takeFile = File(dir, "punch-take.wav").apply { delete() }
        events = CopyOnWriteArrayList()
        startedLatch = CountDownLatch(1)
        finishedLatch = CountDownLatch(1)
        scope.launch {
            recorder.subscribeRecorderEvents().collect { event ->
                events.add(event)
                when (event) {
                    is RecorderEvent.OnStartRecording -> startedLatch.countDown()
                    is RecorderEvent.OnStopRecording,
                    is RecorderEvent.OnMaxDurationReached -> finishedLatch.countDown()
                    else -> Unit
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::recorder.isInitialized && recorder.isRecording) recorder.stopRecording()
        if (::scope.isInitialized) scope.cancel()
        if (::mainFile.isInitialized) {
            mainFile.delete()
            takeFile.delete()
            mainFile.parentFile?.delete()
        }
    }

    @Test
    fun takeSplicesIntoMiddleAndKeepsTail() {
        assertTrue(
            "recorder did not start",
            recorder.startRecording(
                mainFile, 1, 44100, 128_000, 0,
                AudioInput.Mic(MediaRecorder.AudioSource.MIC)
            )
        )
        assertTrue("no audio captured", startedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(6000)
        assertTrue("pause failed", recorder.pauseRecording())

        val anchorMs = 2000L
        assertTrue("take did not start", recorder.startTake(takeFile, anchorMs))
        assertTrue("take flag not set", recorder.isTaking())
        Thread.sleep(2000)
        val newDur = recorder.stopTakeAndSplice()
        assertTrue("splice failed, newDur=$newDur", newDur > 0)
        assertTrue("take flag not cleared", !recorder.isTaking())

        recorder.stopRecording()
        assertTrue("recording did not finish", finishedLatch.await(10, TimeUnit.SECONDS))

        // 6 s main, anchor at 2 s, ~2 s take ending at ~4 s: tail 4-6 s survives.
        assertWavConsistent(mainFile)
        val durationMills = AudioDecoder.readRecordInfo(mainFile).duration / 1000
        assertEquals(6000.0, durationMills.toDouble(), 2500.0)
        val errors = events.filterIsInstance<RecorderEvent.OnError>()
        assertTrue("unexpected errors: $errors", errors.isEmpty())
    }

    @Test
    fun takeLongerThanTailGrowsFile() {
        assertTrue(
            "recorder did not start",
            recorder.startRecording(
                mainFile, 1, 44100, 128_000, 0,
                AudioInput.Mic(MediaRecorder.AudioSource.MIC)
            )
        )
        assertTrue("no audio captured", startedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(3000)
        assertTrue("pause failed", recorder.pauseRecording())

        val anchorMs = 1000L
        assertTrue("take did not start", recorder.startTake(takeFile, anchorMs))
        Thread.sleep(4000)
        val newDur = recorder.stopTakeAndSplice()
        assertTrue("splice failed, newDur=$newDur", newDur > 0)

        recorder.stopRecording()
        assertTrue("recording did not finish", finishedLatch.await(10, TimeUnit.SECONDS))

        // 3 s main, anchor at 1 s, ~4 s take ending at ~5 s: file grows past 3 s.
        assertWavConsistent(mainFile)
        val durationMills = AudioDecoder.readRecordInfo(mainFile).duration / 1000
        assertTrue("expected grown file (~5 s), got $durationMills ms", durationMills > 3500)
        val errors = events.filterIsInstance<RecorderEvent.OnError>()
        assertTrue("unexpected errors: $errors", errors.isEmpty())
    }

    private fun assertWavConsistent(file: File) {
        assertTrue("file missing", file.isFile && file.length() > 44)
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            assertEquals('R'.code.toByte(), header[0])
            assertEquals('I'.code.toByte(), header[1])
            assertEquals('F'.code.toByte(), header[2])
            assertEquals('F'.code.toByte(), header[3])
            assertEquals('W'.code.toByte(), header[8])
            assertEquals('A'.code.toByte(), header[9])
            assertEquals('V'.code.toByte(), header[10])
            assertEquals('E'.code.toByte(), header[11])
            fun le32(off: Int): Long =
                (header[off].toLong() and 0xFF) or
                    ((header[off + 1].toLong() and 0xFF) shl 8) or
                    ((header[off + 2].toLong() and 0xFF) shl 16) or
                    ((header[off + 3].toLong() and 0xFF) shl 24)
            val dataLen = le32(40)
            assertEquals(file.length() - 44, dataLen)
            assertEquals(36 + dataLen, le32(4))
        }
    }

    private fun grantMicPermission(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.RECORD_AUDIO
            )
        }
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}
