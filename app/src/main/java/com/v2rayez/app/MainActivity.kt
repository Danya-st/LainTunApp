package com.v2rayez.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.v2rayez.app.ui.MainViewModel
import com.v2rayez.app.ui.MainVpnScreen
import com.v2rayez.app.ui.theme.V2RayEzTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Тот же экземпляр, что получает hiltViewModel() в MainVpnScreen (ViewModelStore активити).
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тёмная тема всегда: светлые иконки в статус-баре и навигации независимо от системной темы
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        handleAuthRedirect(intent)
        setContent {
            V2RayEzTheme(darkTheme = true) {
                MainVpnScreen(vm)
            }
        }
    }

    // launchMode=singleTask: redirect myvpn://auth из Custom Tab приходит сюда, а не в новый экземпляр.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        intent.data = null // не обрабатывать повторно при пересоздании активити
        vm.onAuthRedirect(data)
    }
}
