package ru.dodix.dodoradar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class ModelVariant(val key: String, val label: String, val fileName: String, val url: String, val approx: String) {
    Q4B(
        "4b", "Qwen3.5 4B: рекомендуется (от 8 ГБ ОЗУ)", "Qwen3.5-4B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf", "≈2,7 ГБ",
    ),
    Q2B(
        "2b", "Qwen3.5 2B: для слабых телефонов", "Qwen3.5-2B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf", "≈1–1,5 ГБ",
    ),
}

data class DlState(val status: String = "idle", val bytes: Long = 0, val total: Long = 0, val message: String = "")

object DownloadState { val state = MutableStateFlow(DlState()) }

class ModelStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun target(prefs: Prefs): Pair<String, String> {          // fileName to url
        if (prefs.variant == "custom" && prefs.customUrl.isNotBlank()) {
            val name = prefs.customUrl.substringBefore('?').substringAfterLast('/').ifBlank { "custom.gguf" }
            return name to prefs.customUrl.trim()
        }
        val v = ModelVariant.values().firstOrNull { it.key == prefs.variant } ?: ModelVariant.Q4B
        return v.fileName to v.url
    }

    fun file(name: String) = File(dir, name)
    fun partFile(name: String) = File(dir, "$name.part")

    /** Установленная и выбранная модель или null. */
    fun activeFile(prefs: Prefs): File? = file(target(prefs).first).takeIf { it.exists() && it.length() > 100_000_000 }

    fun installedSize(prefs: Prefs): Long = activeFile(prefs)?.length() ?: 0

    fun delete(prefs: Prefs) {
        val n = target(prefs).first
        file(n).delete(); partFile(n).delete()
    }

    fun freeBytes(): Long = StatFs(ctx.filesDir.absolutePath).availableBytes

    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

/** Скачивание с докачкой (HTTP Range), проверкой места, сети и SHA-256 (если Hugging Face отдаёт x-linked-etag). */
object ModelDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    private val noRedirect = client.newBuilder().followRedirects(false).build()

    private fun set(s: DlState) { DownloadState.state.value = s }

    /** Бросает IOException с понятным сообщением. isActive — проверка отмены. */
    fun download(ctx: Context, prefs: Prefs, isActive: () -> Boolean) {
        val store = ModelStore(ctx)
        val (name, url) = store.target(prefs)
        if (!prefs.allowMobile && !store.onWifi()) throw IOException("Нужен Wi-Fi (или разрешите мобильную сеть в настройках модели)")

        // 1. размер и контрольная сумма
        set(DlState("running", 0, 0, "Проверяю ссылку…"))
        var total = -1L; var sha: String? = null
        noRedirect.newCall(Request.Builder().url(url).head().header("User-Agent", Http.UA).build()).execute().use { r ->
            when {
                r.code in 300..399 -> {
                    total = r.header("x-linked-size")?.toLongOrNull() ?: -1
                    sha = r.header("x-linked-etag")?.trim('"')?.takeIf { it.length == 64 }
                    if (total < 0) {
                        val loc = r.header("Location") ?: throw IOException("Ссылка перенаправляет в никуда")
                        client.newCall(Request.Builder().url(loc).head().build()).execute().use { r2 ->
                            if (!r2.isSuccessful) throw IOException("Ссылка недоступна (HTTP ${r2.code})")
                            total = r2.header("Content-Length")?.toLongOrNull() ?: -1
                        }
                    }
                }
                r.isSuccessful -> total = r.header("Content-Length")?.toLongOrNull() ?: -1
                else -> throw IOException("Ссылка недоступна (HTTP ${r.code}). Проверьте адрес или выберите другой размер")
            }
            Unit
        }
        if (total <= 0) throw IOException("Сервер не сообщил размер файла")

        val part = store.partFile(name)
        var have = if (part.exists()) part.length() else 0L
        if (have > total) { part.delete(); have = 0 }

        // 2. место
        val need = total - have + 300_000_000L
        if (store.freeBytes() < need) {
            throw IOException("Не хватает места: нужно ещё ${need / 1_000_000} МБ, свободно ${store.freeBytes() / 1_000_000} МБ")
        }

        // 3. скачивание (с докачкой)
        if (have < total) {
            val req = Request.Builder().url(url).header("User-Agent", Http.UA)
                .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            client.newCall(req).execute().use { r ->
                if (r.code == 200 && have > 0) { part.delete(); have = 0 }          // сервер не поддержал Range
                else if (r.code != 200 && r.code != 206) throw IOException("Ошибка загрузки: HTTP ${r.code}")
                val body = r.body ?: throw IOException("Пустой ответ сервера")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(have)
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    body.byteStream().use { ins ->
                        while (true) {
                            if (!isActive()) { set(DlState("paused", have, total, "Остановлено, можно докачать")); throw IOException("Загрузка остановлена") }
                            val n = ins.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); have += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) { set(DlState("running", have, total, "Загрузка…")); last = now }
                        }
                    }
                }
            }
        }
        if (part.length() != total) throw IOException("Файл загружен не полностью (${part.length()} из $total). Нажмите «Скачать» ещё раз")

        // 4. проверка целостности
        sha?.let { expected ->
            set(DlState("running", total, total, "Проверяю контрольную сумму…"))
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { ins ->
                val buf = ByteArray(1024 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                throw IOException("Контрольная сумма не совпала: файл повреждён, скачайте заново")
            }
        }
        val dest = store.file(name)
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Не удалось сохранить файл модели")
        set(DlState("done", total, total, "Модель готова"))
    }
}
