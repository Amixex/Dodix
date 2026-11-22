package ru.dodix.dodoradar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    val prefs = Prefs(app)
    private val store = ModelStore(app)

    val digest = dao.latestDigest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val picks = dao.latestDigest().flatMapLatest { d -> if (d == null) flowOf(emptyList()) else dao.picks(d.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sources = dao.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val logs = dao.logs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val running = RunState.running
    val progress = RunState.message
    val download = DownloadState.state

    val modelInstalled = MutableStateFlow(store.activeFile(prefs) != null)
    val modelSize = MutableStateFlow(store.installedSize(prefs))
    val testResult = MutableStateFlow<String?>(null)
    val variant = MutableStateFlow(prefs.variant)
    val hour = MutableStateFlow(prefs.hour)
    val minute = MutableStateFlow(prefs.minute)
    val daily = MutableStateFlow(prefs.dailyEnabled)
    val message = MutableStateFlow<String?>(null)

    init {
        // обновляем статус модели после окончания загрузки
        viewModelScope.launch { download.collect { if (it.status == "done") refreshModel() } }
    }

    fun refreshModel() {
        modelInstalled.value = store.activeFile(prefs) != null
        modelSize.value = store.installedSize(prefs)
    }

    fun runNow() {
        if (!modelInstalled.value) { message.value = "Сначала загрузите модель (вкладка «Модель»)"; return }
        Scheduler.runNow(getApplication())
    }

    fun setVariant(v: String) { prefs.variant = v; variant.value = v; refreshModel() }
    fun setCustomUrl(u: String) { prefs.customUrl = u; if (u.isNotBlank()) setVariant("custom") }
    fun setAllowMobile(b: Boolean) { prefs.allowMobile = b }
    fun startDownload() = Scheduler.download(getApplication())
    fun cancelDownload() = Scheduler.cancelDownload(getApplication())
    fun deleteModel() { store.delete(prefs); DownloadState.state.value = DlState(); refreshModel() }
    fun freeGb(): String = "%.1f".format(store.freeBytes() / 1e9)

    fun setTime(h: Int, m: Int) {
        prefs.hour = h; prefs.minute = m; hour.value = prefs.hour; minute.value = prefs.minute
        Scheduler.scheduleDaily(getApplication())
    }
    fun setDaily(b: Boolean) { prefs.dailyEnabled = b; daily.value = b; Scheduler.scheduleDaily(getApplication()) }

    fun addSource(name: String, url: String, type: String, selector: String, requireGeo: Boolean) = viewModelScope.launch {
        dao.insertSource(SourceEntity(name = name.ifBlank { url }, url = url.trim(), type = type, selector = selector.trim(), requireGeo = requireGeo))
    }
    fun toggleSource(s: SourceEntity) = viewModelScope.launch { dao.updateSource(s.copy(enabled = !s.enabled)) }
    fun deleteSource(s: SourceEntity) = viewModelScope.launch { dao.deleteSource(s) }

    fun runTest() = viewModelScope.launch {
        testResult.value = "Идёт тест…"
        testResult.value = Radar.test(getApplication())
    }
}
