package ru.dodix.dodoradar

import android.util.Xml
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** text == null означает «тело статьи нужно загрузить отдельно, если публикация новая». */
data class RawItem(val url: String, val title: String, val text: String?, val publishedAt: Long?)

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36 DodixRadar/1.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ru,en;q=0.8")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("пустой ответ")
        }
    }
}

object Fetchers {
    fun fetch(s: SourceEntity): List<RawItem> = when (s.type) {
        "rss" -> parseRss(Http.get(s.url))
        "tg" -> fetchTelegram(s.url)
        "html" -> fetchHtmlList(s)
        else -> throw IOException("неизвестный тип источника: ${s.type}")
    }

    fun normalizeUrl(u: String): String {
        val h = u.toHttpUrlOrNull() ?: return u.trim()
        val b = h.newBuilder().fragment(null)
        h.queryParameterNames.filter { it.startsWith("utm_") || it in setOf("yclid", "fbclid", "gclid", "ysclid") }
            .forEach { b.removeAllQueryParameters(it) }
        return b.build().toString().removeSuffix("/")
    }

    // ---------- RSS / Atom ----------
    fun parseRss(xml: String): List<RawItem> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val out = mutableListOf<RawItem>()
        var inItem = false
        var title = ""; var link = ""; var desc = ""; var date = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase()
                    if (n == "item" || n == "entry") {
                        inItem = true; title = ""; link = ""; desc = ""; date = ""
                    } else if (inItem) {
                        when (n) {
                            "title" -> title = p.nextText()
                            "link" -> {
                                val href = p.getAttributeValue(null, "href")
                                val t = p.nextText()
                                if (link.isEmpty()) link = if (!href.isNullOrBlank()) href else t.trim()
                            }
                            "description", "summary", "content", "content:encoded" -> {
                                val t = p.nextText()
                                if (desc.isEmpty()) desc = t
                            }
                            "pubdate", "published", "updated", "dc:date" -> {
                                val t = p.nextText()
                                if (date.isEmpty()) date = t
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = p.name.lowercase()
                    if ((n == "item" || n == "entry") && inItem) {
                        inItem = false
                        if (title.isNotBlank() && link.isNotBlank()) {
                            out += RawItem(
                                link.trim(), Jsoup.parse(title).text().trim(),
                                Jsoup.parse(desc).text().take(3000), parseDate(date),
                            )
                        }
                    }
                }
            }
            ev = p.next()
        }
        if (out.isEmpty()) throw IOException("лента пуста или это не RSS/Atom")
        return out
    }

    // ---------- публичный Telegram-канал (веб-превью t.me/s/...) ----------
    private fun fetchTelegram(url: String): List<RawItem> {
        val m = Regex("""t\.me/(?:s/)?([A-Za-z0-9_]{4,})""").find(url)
            ?: throw IOException("ожидается ссылка вида https://t.me/s/<канал>")
        val doc = Jsoup.parse(Http.get("https://t.me/s/${m.groupValues[1]}"), "https://t.me")
        val items = doc.select(".tgme_widget_message_wrap").mapNotNull { w ->
            val text = w.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
            val link = w.selectFirst("a.tgme_widget_message_date")?.attr("href").orEmpty()
            if (text.isBlank() || link.isBlank()) null else RawItem(
                link,
                if (text.length > 120) text.take(117) + "…" else text,
                text.take(3000),
                parseDate(w.selectFirst("time")?.attr("datetime")),
            )
        }
        if (items.isEmpty()) throw IOException("публичное превью канала недоступно или пусто")
        return items
    }

    // ---------- HTML-список новостей по CSS-селектору ----------
    private fun fetchHtmlList(s: SourceEntity): List<RawItem> {
        val doc = Jsoup.parse(Http.get(s.url), s.url)
        val host = s.url.toHttpUrl().host
        val sel = s.selector.ifBlank { "a" }
        val items = doc.select(sel).mapNotNull { a ->
            val href = a.absUrl("href")
            val t = a.text().trim()
            if (href.isBlank() || t.length < 25) null else href to t
        }.filter { (href, _) ->
            href != s.url && href.toHttpUrlOrNull()?.host == host
        }.distinctBy { it.first }.take(20)
            .map { (href, t) -> RawItem(href, t, null, null) }
        if (items.isEmpty()) throw IOException("не найдено ссылок по селектору «$sel» (если страница строится JavaScript, нужен RSS)")
        return items
    }

    /** Загрузка тела статьи для html-источников. */
    fun fetchArticle(url: String): Pair<String, Long?> {
        val doc = Jsoup.parse(Http.get(url), url)
        val date = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
            ?: doc.selectFirst("time[datetime]")?.attr("datetime")
        doc.select("script,style,nav,header,footer,aside,form,noscript").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        return main.text().trim().take(3000) to parseDate(date)
    }

    fun parseDate(s: String?): Long? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return null
        return runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
    }
}
