package ru.dodix.dodoradar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DodoTheme {
                val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val installed by vm.modelInstalled.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(if (installed) 0 else 3) }
                val msg by vm.message.collectAsState()
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

                Scaffold(
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        NavigationBar {
                            listOf("🍕 Главная", "📡 Источники", "⚙️ Настройки", "🧠 Модель").forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                    icon = { Text(t.substringBefore(' ')) }, label = { Text(t.substringAfter(' ')) })
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> HomeScreen(vm, m) { tab = 3 }
                        1 -> SourcesScreen(vm, m)
                        2 -> SettingsScreen(vm, m)
                        else -> ModelScreen(vm, m)
                    }
                }
            }
        }
    }
}
