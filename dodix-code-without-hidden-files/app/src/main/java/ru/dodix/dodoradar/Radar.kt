package ru.dodix.dodoradar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.dodix.dodoradar.llm.LlamaEngine

object RunState {
    val running = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
}

data class RunResult(val ok: Boolean, val picks: Int, val message: String)

object Radar {
    private val lock = Mutex()
    private const val WINDOW_MS = 48L * 3600_000

    suspend fun ensureDefaults(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        if (dao.sourceCount() == 0) {
            // Только то, что дал владелец. Канал проверен (публичное превью t.me/s/ открывается).
            dao.insertSource(SourceEntity(
                name = "Щелково • Ивантеевка • Фрязино (Telegram)",
                url = "https://t.me/s/shelchok_NEWS", type = "tg", requireGeo = true,
            ))
            // Страница из ссылки владельца; это раздел «Бизнес», не лента новостей: выключен до проверки.
            dao.insertSource(SourceEntity(
                name = "Администрация Фрязино (fryazino.info)",
                url = "https://www.fryazino.info/business/5458", type = "html", requireGeo = false, enabled = false,
                lastStatus = "не проверен: укажите адрес раздела новостей",
            ))
        }
    }

    private fun say(s: String) { RunState.message.value = s }

    suspend fun run(ctx: Context): RunResult {
        if (!lock.tryLock()) return RunResult(false, 0, "Сводка уже готовится")
        RunState.running.value = true
        try {
            return withContext(Dispatchers.Default) { doRun(ctx.applicationContext) }
        } finally {
            RunState.running.value = false
            RunState.message.value = null
            LlamaEngine.unload()
            lock.unlock()
        }
    }

    private suspend fun doRun(ctx: Context): RunResult {
        val db = AppDb.get(ctx); val dao = db.dao(); val prefs = Prefs(ctx)
        val store = ModelStore(ctx)
        val model = store.activeFile(prefs)
        if (model == null) {
            Log.add(ctx, "error", "Модель не загружена: сводка невозможна")
            return RunResult(false, 0, "Сначала загрузите модель (вкладка «Модель»)")
        }
        val now = System.currentTimeMillis()
        dao.purgeNews(now - 14L * 86400_000)
        dao.purgeLogs(now - 14L * 86400_000)

        // ---- 1. сбор ----
        val kw = prefs.keywordList(); val geo = prefs.geoList()
        var fresh = 0
        for (src in dao.enabledSources()) {
            say("Источник: ${src.name}")
            try {
                val items = withContext(Dispatchers.IO) { Fetchers.fetch(src) }
                var added = 0
                for (item in items) {
                    val url = Fetchers.normalizeUrl(item.url)
                    if (dao.newsExists(url) > 0) continue
                    var text = item.text; var published = item.publishedAt
                    if (text == null) {
                        delay(700)
                        val (t, d) = try { withContext(Dispatchers.IO) { Fetchers.fetchArticle(url) } } catch (e: Exception) { "" to null }
                        text = t; published = published ?: d
                    }
                    val ts = published ?: now
                    if (now - ts > WINDOW_MS) continue
                    val hay = (item.title + " " + text).lowercase()
                    val geoOk = !src.requireGeo || geo.isEmpty() || geo.any { g -> hay.contains(g) }
                    val score = kw.count { k -> hay.contains(k) }
                    val relevant = geoOk && score > 0
                    // неподходящие тоже записываем (analyzed=true), чтобы не разбирать их повторно
                    val ins = dao.insertNews(NewsEntity(url, src.name, item.title, text.take(2000), ts, now, score, analyzed = !relevant))
                    if (ins != -1L && relevant) { added++; fresh++ }
                }
                dao.updateSource(src.copy(lastStatus = "ок", lastCount = items.size, lastCheckedAt = now, lastError = ""))
                Log.add(ctx, "info", "«${src.name}»: получено ${items.size}, новых подходящих $added")
            } catch (e: Exception) {
                dao.updateSource(src.copy(lastStatus = "ошибка", lastCheckedAt = now, lastError = e.message ?: e.toString()))
                Log.add(ctx, "error", "«${src.name}» пропущен: ${e.message}")
            }
            delay(800) // пауза между источниками
        }

        // ---- 2. отбор кандидатов ----
        val pending = dao.pending(now - WINDOW_MS, 12)
        if (pending.isEmpty()) {
            dao.insertDigest(DigestEntity(createdAt = now, newCount = fresh, candidateCount = 0, note = "Новых подходящих публикаций нет"))
            Notifier.digest(ctx, 0)
            return RunResult(true, 0, "Новых подходящих публикаций нет")
        }

        // ---- 3. локальная модель ----
        say("Загружаю модель…")
        val err = withContext(Dispatchers.IO) { LlamaEngine.load(model.absolutePath, Analyzer.N_CTX, 4) }
        if (err != null) {
            Log.add(ctx, "error", err)
            return RunResult(false, 0, err)
        }
        val cands = pending.map { Candidate(it.url, it.title, it.text) }
        val batches = Analyzer.makeBatches(cands, prefs.textLimit)
        val all = mutableListOf<PickEntity>()
        var failed = 0
        for ((bi, batch) in batches.withIndex()) {
            say("Анализ ${bi + 1}/${batches.size} (${batch.size} новостей)…")
            val picks = withContext(Dispatchers.Default) { Analyzer.analyzeBatch(batch, prefs.textLimit) { m -> Log.addBlocking(ctx, m) } }
            if (picks == null) { failed++; continue }
            dao.markAnalyzed(batch.map { it.url })   // не разобранные из-за сбоя останутся на следующий раз
            for (p in picks) {
                val c = batch[p.index]
                all += PickEntity(digestId = 0, url = c.url, title = c.title, idea = p.idea, reason = p.reason, score = p.score)
            }
        }
        LlamaEngine.unload()
        val top = all.sortedByDescending { it.score }.take(3)
        val note = when {
            failed == batches.size -> "Модель не смогла дать валидный ответ; публикации сохранены, повторю позже"
            top.isEmpty() -> "Ничего подходящего для маркетинга пиццерии"
            else -> ""
        }
        val digestId = dao.insertDigest(DigestEntity(createdAt = System.currentTimeMillis(), newCount = fresh, candidateCount = cands.size, note = note))
        if (top.isNotEmpty()) dao.insertPicks(top.map { it.copy(digestId = digestId) })
        Log.add(ctx, "info", "Сводка готова: кандидатов ${cands.size}, идей ${top.size}, сбоев пакетов $failed")
        Notifier.digest(ctx, top.size)
        return RunResult(failed < batches.size, top.size, note.ifEmpty { "Готово" })
    }

    /** Тест без интернета: 3 встроенные новости (выдуманы для проверки модели, помечены [ТЕСТ]). */
    suspend fun test(ctx: Context): String = withContext(Dispatchers.Default) {
        if (!lock.tryLock()) return@withContext "Занято: идёт построение сводки"
        RunState.running.value = true
        try {
            val prefs = Prefs(ctx); val model = ModelStore(ctx).activeFile(prefs)
                ?: return@withContext "Модель не загружена"
            say("Тест: загружаю модель…")
            val err = LlamaEngine.load(model.absolutePath, Analyzer.N_CTX, 4)
            if (err != null) return@withContext err
            val samples = listOf(
                Candidate("test://1", "[ТЕСТ] Во Фрязине пройдёт городская ярмарка выходного дня",
                    "В субботу на центральной площади Фрязина с 11:00 до 17:00 пройдёт осенняя ярмарка: выставка работ детских кружков, концерт местных коллективов, конкурсы для школьников. Вход свободный, организаторы ожидают семьи с детьми."),
                Candidate("test://2", "[ТЕСТ] В фрязинских школах начались осенние каникулы",
                    "Во всех школах Фрязина с понедельника начались осенние каникулы, они продлятся девять дней. Городской Дом культуры подготовил для школьников программу мастер-классов и квест по городу."),
                Candidate("test://3", "[ТЕСТ] Во Фрязине отключат горячую воду на двух улицах",
                    "В связи с плановым ремонтом сетей горячая вода будет отключена на улицах Центральной и Институтской с 8:00 до 20:00. Коммунальные службы просят жителей запастись водой."),
            )
            val t0 = System.currentTimeMillis()
            val picks = Analyzer.analyzeBatch(samples, prefs.textLimit) { m -> Log.addBlocking(ctx, m) }
            val sec = (System.currentTimeMillis() - t0) / 1000
            if (picks == null) "Модель загрузилась, но не вернула валидный JSON (подробности в журнале). $sec с"
            else "Готово за $sec с. Выбрано: ${picks.size} из 3 (ожидаемо 1–2; новость про воду должна быть отброшена)\n\n" +
                picks.joinToString("\n\n") { "• ${samples[it.index].title}\n💡 ${it.idea}\nПочему: ${it.reason}\n(оценка ${it.score}/10)" }
        } catch (e: Exception) {
            "Ошибка теста: ${e.message}"
        } finally {
            LlamaEngine.unload(); RunState.running.value = false; RunState.message.value = null; lock.unlock()
        }
    }
}
