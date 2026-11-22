package ru.dodix.dodoradar

import org.json.JSONObject
import ru.dodix.dodoradar.llm.LlamaEngine
import kotlin.random.Random

data class Candidate(val url: String, val title: String, val text: String)
data class Pick(val index: Int, val score: Int, val idea: String, val reason: String)

/** Промпт, грамматика JSON и разбор ответа локальной модели. */
object Analyzer {
    const val N_CTX = 3584
    const val MAX_NEW_TOKENS = 640
    private const val CHAR_BUDGET = 5200   // сколько символов новостей помещаем в один запрос
    private const val MAX_PER_BATCH = 6

    private const val SYSTEM = "Ты помощник локального маркетолога пиццерии «Додо Пицца» в городе Фрязино (Московская область). " +
        "Из списка городских новостей выбери не более 3, которые реально полезны для локального маркетинга пиццерии: " +
        "события с большим числом людей, школьники и студенты, семьи с детьми, праздники, спорт, открытия, изменения, влияющие на посещаемость. " +
        "Для каждой выбранной новости дай ОДНУ конкретную идею активности (что сделать, где, для кого) и причину, опираясь ТОЛЬКО на факты из текста этой новости. " +
        "Ничего не выдумывай: не придумывай даты, цифры, договорённости, партнёров и результаты. " +
        "Если ничего не подходит, верни пустой список. Отвечай только JSON."

    /** GBNF: гарантирует валидный JSON не более 3 пунктов. */
    val GRAMMAR = """
root ::= "{" ws "\"picks\"" ws ":" ws "[" ws (pick (ws "," ws pick (ws "," ws pick)?)?)? ws "]" ws "}"
pick ::= "{" ws "\"id\"" ws ":" ws num ws "," ws "\"score\"" ws ":" ws sc ws "," ws "\"idea\"" ws ":" ws str ws "," ws "\"reason\"" ws ":" ws str ws "}"
num ::= [1-9] [0-9]?
sc ::= "10" | [1-9]
str ::= "\"" ([^"\\\n\r] | "\\" ["\\/nrt])* "\""
ws ::= " "?
""".trimIndent()

    fun makeBatches(list: List<Candidate>, textLimit: Int): List<List<Candidate>> {
        val batches = mutableListOf<MutableList<Candidate>>()
        var cur = mutableListOf<Candidate>()
        var chars = 0
        for (c in list) {
            val len = c.title.length + minOf(c.text.length, textLimit) + 10
            if (cur.isNotEmpty() && (chars + len > CHAR_BUDGET || cur.size >= MAX_PER_BATCH)) {
                batches += cur; cur = mutableListOf(); chars = 0
            }
            cur += c; chars += len
        }
        if (cur.isNotEmpty()) batches += cur
        return batches
    }

    fun buildPrompt(batch: List<Candidate>, textLimit: Int): String {
        val news = batch.mapIndexed { i, c ->
            "[${i + 1}] ${c.title}\n${c.text.take(textLimit)}"
        }.joinToString("\n\n")
        val user = "Новости:\n\n$news\n\n" +
            "Верни JSON: {\"picks\":[{\"id\":<номер новости>,\"score\":<польза 1-10>,\"idea\":\"<идея до 200 символов>\",\"reason\":\"<причина до 150 символов>\"}]}. " +
            "Если ни одна новость не подходит: {\"picks\":[]}."
        // Режим без размышлений для Qwen3.5: пустой блок <think> в начале ответа ассистента.
        return "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    /** null — ответ невалиден. */
    fun parse(raw: String, n: Int): List<Pick>? = try {
        val cleaned = raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val s = cleaned.indexOf('{')
        val e = cleaned.lastIndexOf('}')
        if (s < 0 || e <= s) null else {
            val arr = JSONObject(cleaned.substring(s, e + 1)).optJSONArray("picks")
            if (arr == null) null else {
                val out = mutableListOf<Pick>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("id", -1)
                    val idea = o.optString("idea").trim()
                    val reason = o.optString("reason").trim()
                    if (id in 1..n && idea.length >= 10 && reason.length >= 5) {
                        out += Pick(id - 1, o.optInt("score", 5).coerceIn(1, 10), idea, reason)
                    }
                }
                if (arr.length() > 0 && out.isEmpty()) null else out.distinctBy { it.index }.take(3)
            }
        }
    } catch (e: Exception) { null }

    /** До 3 попыток (1 + 2 повтора при невалидном JSON). null — не удалось получить валидный ответ. */
    fun analyzeBatch(batch: List<Candidate>, textLimit: Int, log: (String) -> Unit): List<Pick>? {
        val prompt = buildPrompt(batch, textLimit)
        for (attempt in 0..2) {
            val raw = LlamaEngine.generate(
                prompt, GRAMMAR, MAX_NEW_TOKENS,
                temp = if (attempt == 0) 0.3f else 0.5f, topK = 20, topP = 0.8f, seed = Random.nextInt(1, Int.MAX_VALUE),
            )
            if (raw.startsWith("@@ERR:")) { log("ошибка модели: ${raw.removePrefix("@@ERR:")}"); return null }
            val parsed = parse(raw, batch.size)
            if (parsed != null) return parsed
            log("невалидный JSON (попытка ${attempt + 1}/3): ${raw.take(120)}")
        }
        return null
    }
}
