package com.rama.riya

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * Offline speech-to-text using Vosk. Owns the microphone while running.
 * Audio is thrown away while [pause]d (so Riya never hears her own voice).
 */
class VoskEars(
    private val ctx: Context,
    private val modelName: String,
    private val onReady: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onFail: (String) -> Unit
) {
    @Volatile private var alive = true
    @Volatile private var paused = true
    @Volatile private var resumeAt = 0L

    fun start() {
        val t = Thread { runLoop() }
        t.isDaemon = true
        t.start()
    }

    fun pause() { paused = true }

    fun resume() {
        if (paused) {
            resumeAt = System.currentTimeMillis() + 450   // skip the tail of Riya's own voice
            paused = false
        }
    }

    fun stop() { alive = false }

    private fun copyAssets(path: String, dest: File) {
        val am = ctx.assets
        val names = am.list(path)
        if (names == null || names.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(dest).use { out -> input.copyTo(out) } }
        } else {
            dest.mkdirs()
            for (n in names) copyAssets("$path/$n", File(dest, n))
        }
    }

    private fun prepareModel(): File {
        val src = "models/$modelName"
        val list = ctx.assets.list(src)
        if (list == null || list.isEmpty()) throw IllegalStateException("speech model '$modelName' is not inside the app")
        val dest = File(ctx.filesDir, "vosk/$modelName")
        val ok = File(dest, ".copied")
        if (!ok.exists()) {
            dest.deleteRecursively()
            copyAssets(src, dest)
            ok.writeText("1")
        }
        return dest
    }

    private fun runLoop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var mic: AudioRecord? = null
        try {
            val dir = prepareModel()
            val m = Model(dir.absolutePath)
            model = m
            val r = Recognizer(m, 16000.0f)
            rec = r

            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 16000 * 2)
            )
            mic = a
            if (a.state != AudioRecord.STATE_INITIALIZED) {
                if (alive) onFail("microphone could not be opened")
                return
            }
            a.startRecording()
            onReady()

            val buf = ShortArray(1600)      // 100 ms
            var lastPartial = ""
            var needReset = true
            while (alive) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }
                if (paused || System.currentTimeMillis() < resumeAt) { needReset = true; continue }
                if (needReset) { r.reset(); lastPartial = ""; needReset = false }
                if (r.acceptWaveForm(buf, n)) {
                    val text = JSONObject(r.result).optString("text").trim()
                    lastPartial = ""
                    if (text.isNotEmpty()) onText(text)
                } else {
                    val p = JSONObject(r.partialResult).optString("partial").trim()
                    if (p.isNotEmpty() && p != lastPartial) { lastPartial = p; onPartial(p) }
                }
            }
        } catch (e: Throwable) {
            if (alive) onFail(e.message ?: e.javaClass.simpleName)
        } finally {
            try { mic?.stop() } catch (e: Throwable) { }
            try { mic?.release() } catch (e: Throwable) { }
            try { rec?.close() } catch (e: Throwable) { }
            try { model?.close() } catch (e: Throwable) { }
        }
    }
}
