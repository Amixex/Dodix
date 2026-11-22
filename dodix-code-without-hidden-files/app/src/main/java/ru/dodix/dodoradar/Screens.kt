package ru.dodix.dodoradar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt get() = SimpleDateFormat("d MMMM, EEEE", Locale("ru"))
private val timeFmt get() = SimpleDateFormat("d MMM HH:mm", Locale("ru"))

@Composable
private fun Screen(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

// ===================== Главная =====================
@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier, openModel: () -> Unit) {
    val picks by vm.picks.collectAsState()
    val digest by vm.digest.collectAsState()
    val running by vm.running.collectAsState()
    val progress by vm.progress.collectAsState()
    val installed by vm.modelInstalled.collectAsState()
    val uri = LocalUriHandler.current

    Screen(modifier) {
        Text("🍕 Додо Радар", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(dayFmt.format(Date()).replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!installed) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Модель ещё не загружена", fontWeight = FontWeight.SemiBold)
                    Text("Без неё приложение не может анализировать новости.")
                    Button(onClick = openModel) { Text("Загрузить модель") }
                }
            }
        }

        Button(onClick = { vm.runNow() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Идёт работа…" else "Обновить сейчас")
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Телефон может нагреваться, это нормально. Не закрывайте приложение.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        digest?.let { d ->
            Text("Сводка от ${timeFmt.format(Date(d.createdAt))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (picks.isEmpty()) {
                Card { Text(d.note.ifBlank { "Сегодня ничего подходящего" }, Modifier.padding(16.dp)) }
            }
        } ?: Text("Сводок пока нет. Нажмите «Обновить сейчас» или дождитесь автосводки.")

        picks.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    OutlinedButton(onClick = { runCatching { uri.openUri(p.url) } }) { Text("Открыть новость") }
                    Text("💡 ${p.idea}")
                    Text("Почему: ${p.reason}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Проверь факты по ссылке. Идеи подготовлены моделью на телефоне и могут ошибаться.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===================== Источники =====================
@Composable
fun SourcesScreen(vm: MainViewModel, modifier: Modifier) {
    val sources by vm.sources.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Источники", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { dialog = true }) { Text("+ Добавить") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { s ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (!s.enabled) "⏸" else if (s.lastStatus == "ок") "🟢" else if (s.lastStatus == "ошибка") "🔴" else "⚪")
                            Spacer(Modifier.width(8.dp))
                            Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(checked = s.enabled, onCheckedChange = { vm.toggleSource(s) })
                        }
                        Text("${s.type.uppercase()} · ${s.url}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Статус: ${s.lastStatus} · получено: ${s.lastCount}" +
                            if (s.lastCheckedAt > 0) " · ${timeFmt.format(Date(s.lastCheckedAt))}" else "", fontSize = 12.sp)
                        if (s.lastError.isNotBlank()) Text("Ошибка: ${s.lastError}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.deleteSource(s) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
    if (dialog) AddSourceDialog({ dialog = false }) { n, u, t, sel, geo -> vm.addSource(n, u, t, sel, geo); dialog = false }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("rss") }
    var selector by remember { mutableStateOf("") }
    var geo by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый источник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("rss" to "RSS", "tg" to "Telegram", "html" to "Сайт").forEach { (k, l) ->
                        FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(if (type == "tg") "https://t.me/s/канал" else "https://…") }, singleLine = true)
                if (type == "html") OutlinedTextField(selector, { selector = it }, label = { Text("CSS-селектор ссылок (по умолч. a)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(geo, { geo = it }); Text("Только публикации с упоминанием города", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (url.startsWith("http")) onAdd(name, url, type, selector, geo) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ===================== Настройки =====================
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val hour by vm.hour.collectAsState()
    val minute by vm.minute.collectAsState()
    val daily by vm.daily.collectAsState()
    val test by vm.testResult.collectAsState()
    val running by vm.running.collectAsState()
    val logs by vm.logs.collectAsState()
    var keywords by remember { mutableStateOf(vm.prefs.keywords) }
    var geo by remember { mutableStateOf(vm.prefs.geoWords) }
    var limit by remember { mutableFloatStateOf(vm.prefs.textLimit.toFloat()) }
    var showLogs by remember { mutableStateOf(false) }

    Screen(modifier) {
        Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Автосводка каждый день", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(daily, { vm.setDaily(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Время:")
                OutlinedButton(onClick = { vm.setTime((hour + 23) % 24, minute) }) { Text("−") }
                Text("%02d".format(hour), fontSize = 20.sp)
                OutlinedButton(onClick = { vm.setTime((hour + 1) % 24, minute) }) { Text("+") }
                Text(":")
                OutlinedButton(onClick = { vm.setTime(hour, (minute + 55) % 60) }) { Text("−") }
                Text("%02d".format(minute), fontSize = 20.sp)
                OutlinedButton(onClick = { vm.setTime(hour, (minute + 5) % 60) }) { Text("+") }
            }
            Text("Запуск при подключении к сети и при зарядке или заряде от 30 %. Чтобы Samsung не усыплял приложение, отключите ограничения батареи.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("Открыть настройки приложения (батарея)") }
        } }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ключевые слова (грубый фильтр)", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(keywords, { keywords = it; vm.prefs.keywords = it }, Modifier.fillMaxWidth(), minLines = 3,
                supportingText = { Text("Через запятую; ищутся как части слов, регистр не важен") })
            TextButton(onClick = { keywords = Prefs.DEFAULT_KEYWORDS; vm.prefs.keywords = keywords }) { Text("Сбросить к стандартным") }
            Text("Обязательное слово города (для источников с «только город»)", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(geo, { geo = it; vm.prefs.geoWords = it }, Modifier.fillMaxWidth(), singleLine = true)
            Text("Длина текста новости для модели: ${limit.toInt()} символов", fontWeight = FontWeight.SemiBold)
            Slider(limit, { limit = it; vm.prefs.textLimit = it.toInt() }, valueRange = 500f..1500f, steps = 9)
        } }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тест модели без интернета", fontWeight = FontWeight.SemiBold)
            Text("Прогоняет 3 встроенные новости (помечены [ТЕСТ]).", fontSize = 13.sp)
            Button(onClick = { vm.runTest() }, enabled = !running) { Text("Тест") }
            test?.let { Text(it, fontSize = 13.sp) }
        } }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Журнал", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Скрыть" else "Показать") }
            }
            if (showLogs) {
                if (logs.isEmpty()) Text("Пусто", fontSize = 13.sp)
                logs.take(60).forEach {
                    Text("${timeFmt.format(Date(it.ts))} [${it.level}] ${it.msg}", fontSize = 11.sp,
                        color = if (it.level == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        } }
    }
}

// ===================== Модель =====================
@Composable
fun ModelScreen(vm: MainViewModel, modifier: Modifier) {
    val installed by vm.modelInstalled.collectAsState()
    val size by vm.modelSize.collectAsState()
    val variant by vm.variant.collectAsState()
    val dl by vm.download.collectAsState()
    var mobile by remember { mutableStateOf(vm.prefs.allowMobile) }
    var custom by remember { mutableStateOf(vm.prefs.customUrl) }
    var confirmDelete by remember { mutableStateOf(false) }

    Screen(modifier) {
        Text("Загрузка модели", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Модель хранится на телефоне и работает без интернета. Скачивается один раз с Hugging Face. Свободно: ${vm.freeGb()} ГБ.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ModelVariant.values().forEach { v ->
            Card(colors = CardDefaults.cardColors(containerColor =
                if (variant == v.key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(variant == v.key, { vm.setVariant(v.key) })
                    Column { Text(v.label, fontWeight = FontWeight.SemiBold); Text(v.approx, fontSize = 12.sp) }
                }
            }
        }
        OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Или своя ссылка на .gguf (зеркало)") },
            supportingText = { Text("Например, если Hugging Face недоступен") })
        if (custom.isNotBlank()) OutlinedButton(onClick = { vm.setCustomUrl(custom) }) { Text("Использовать эту ссылку") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(mobile, { mobile = it; vm.setAllowMobile(it) })
            Spacer(Modifier.width(8.dp)); Text("Разрешить мобильную сеть (по умолчанию только Wi-Fi)", fontSize = 13.sp)
        }

        if (installed) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("✅ Модель установлена (${size / 1_000_000} МБ)", Modifier.padding(16.dp))
            }
        }
        when (dl.status) {
            "running" -> {
                val frac = if (dl.total > 0) dl.bytes.toFloat() / dl.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text("${dl.message} ${dl.bytes / 1_000_000} / ${dl.total / 1_000_000} МБ (${(frac * 100).toInt()}%)", fontSize = 13.sp)
                OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Остановить") }
            }
            "error" -> Text("❌ ${dl.message}", color = MaterialTheme.colorScheme.error)
            "paused" -> Text("⏸ ${dl.message}")
        }
        if (dl.status != "running") {
            Button(onClick = { vm.startDownload() }, enabled = !installed, modifier = Modifier.fillMaxWidth()) {
                Text(if (dl.status == "paused" || dl.status == "error") "Докачать" else "Скачать")
            }
        }
        if (installed) OutlinedButton(onClick = { confirmDelete = true }) { Text("Удалить модель") }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить модель?") }, text = { Text("Файл будет удалён. Скачать можно снова.") },
        confirmButton = { TextButton(onClick = { vm.deleteModel(); confirmDelete = false }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}
