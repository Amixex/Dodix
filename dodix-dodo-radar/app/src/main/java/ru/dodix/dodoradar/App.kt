package ru.dodix.dodoradar

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Radar.ensureDefaults(this@App)
            Scheduler.scheduleDaily(this@App)
        }
    }
}
