package com.rama.riya

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/** A small chat model that runs fully on the phone (MediaPipe LLM Inference, free, offline). */
class LocalLlm(private val ctx: Context) {

    companion object {
        fun file(ctx: Context) = File(ctx.filesDir, "llm/model.task")
        fun installed(ctx: Context): Boolean {
            val f = file(ctx)
            return f.exists() && f.length() > 20_000_000L
        }
    }

    @Volatile private var llm: LlmInference? = null

    fun isLoaded() = llm != null

    /** Returns null when loaded, otherwise a message explaining what went wrong. */
    @Synchronized
    fun load(): String? {
        if (llm != null) return null
        if (!installed(ctx)) return "Offline brain file is not installed yet."
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx).absolutePath)
                .setMaxTokens(1024)
                .build()
            llm = LlmInference.createFromOptions(ctx, opts)
            null
        } catch (e: Throwable) {
            "Could not load the offline brain: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun ask(name: String, past: List<Pair<String, String>>, question: String): String? {
        val m = llm ?: return null
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are ").append(name).append(", a friendly voice assistant on the user's phone. ")
        sb.append("Answer in one to three short spoken sentences. No markdown, no lists, no emojis.<|im_end|>\n")
        for ((role, text) in past) {
            sb.append("<|im_start|>").append(role).append("\n").append(text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(question).append("<|im_end|>\n<|im_start|>assistant\n")
        val out = m.generateResponse(sb.toString()) ?: return null
        return out.substringBefore("<|im_end|>").substringBefore("<|im_start|>").trim()
    }

    @Synchronized
    fun close() {
        try { llm?.close() } catch (e: Throwable) { }
        llm = null
    }
}
