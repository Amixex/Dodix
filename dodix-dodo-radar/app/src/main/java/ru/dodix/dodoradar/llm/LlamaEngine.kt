package ru.dodix.dodoradar.llm

/** Тонкая обёртка над libdodollm.so (llama.cpp). Одновременно загружена не более одной модели. */
object LlamaEngine {
    private var libLoaded = false
    private var handle = 0L

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, grammar: String,
        maxTokens: Int, temp: Float, topK: Int, topP: Float, seed: Int,
    ): ByteArray
    private external fun nativeFree(handle: Long)
    private external fun nativeAbort()
    private external fun nativeSystemInfo(): String

    val isLoaded: Boolean get() = handle != 0L

    /** Возвращает null при успехе или текст ошибки. */
    @Synchronized
    fun load(path: String, nCtx: Int, threads: Int): String? {
        try {
            if (!libLoaded) {
                System.loadLibrary("dodollm")
                libLoaded = true
            }
            if (handle != 0L) return null
            handle = nativeLoad(path, nCtx, threads)
            return if (handle == 0L) {
                "Не удалось загрузить модель: не хватает памяти или файл повреждён/не поддерживается"
            } else null
        } catch (e: UnsatisfiedLinkError) {
            return "Нативная библиотека не загружена: ${e.message}"
        }
    }

    /** Результат с префиксом "@@ERR:" — ошибка инференса. */
    fun generate(
        prompt: String, grammar: String, maxTokens: Int,
        temp: Float, topK: Int, topP: Float, seed: Int,
    ): String {
        val h = handle
        if (h == 0L) return "@@ERR:модель не загружена"
        return String(nativeGenerate(h, prompt, grammar, maxTokens, temp, topK, topP, seed), Charsets.UTF_8)
    }

    fun abort() { if (libLoaded) nativeAbort() }

    @Synchronized
    fun unload() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    fun systemInfo(): String = if (libLoaded) nativeSystemInfo() else "библиотека не загружена"
}
