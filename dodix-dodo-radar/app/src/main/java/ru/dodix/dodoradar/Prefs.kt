package ru.dodix.dodoradar

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("radar", Context.MODE_PRIVATE)

    var hour: Int
        get() = sp.getInt("hour", 9)
        set(v) = sp.edit().putInt("hour", v.coerceIn(0, 23)).apply()
    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = sp.edit().putInt("minute", v.coerceIn(0, 59)).apply()
    var dailyEnabled: Boolean
        get() = sp.getBoolean("daily", true)
        set(v) = sp.edit().putBoolean("daily", v).apply()
    var variant: String
        get() = sp.getString("variant", "4b") ?: "4b"
        set(v) = sp.edit().putString("variant", v).apply()
    var customUrl: String
        get() = sp.getString("customUrl", "") ?: ""
        set(v) = sp.edit().putString("customUrl", v).apply()
    var allowMobile: Boolean
        get() = sp.getBoolean("allowMobile", false)
        set(v) = sp.edit().putBoolean("allowMobile", v).apply()
    var keywords: String
        get() = sp.getString("keywords", DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(v) = sp.edit().putString("keywords", v).apply()
    var geoWords: String
        get() = sp.getString("geo", "фрязин") ?: "фрязин"
        set(v) = sp.edit().putString("geo", v).apply()
    var textLimit: Int
        get() = sp.getInt("textLimit", 1000)
        set(v) = sp.edit().putInt("textLimit", v.coerceIn(500, 1500)).apply()

    fun keywordList(): List<String> = splitList(keywords)
    fun geoList(): List<String> = splitList(geoWords)

    companion object {
        const val DEFAULT_KEYWORDS =
            "школ, каникул, выпускн, открыти, фестивал, ярмарк, праздник, спорт, конкурс, молодёж, молодеж, акци, " +
            "концерт, детск, дети, студент, соревнован, турнир, день города, масленица, новый год, ёлк, елк, " +
            "квест, марафон, забег, выставк, футбол, хоккей, каток, бесплатн, подарк"

        fun splitList(s: String): List<String> =
            s.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}
