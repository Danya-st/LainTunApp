package com.v2rayez.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2rayez.app.data.api.Plan
import com.v2rayez.app.data.service.VpnStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainVpnScreen(vm: MainViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.startVpnService() else vm.onPermissionDenied()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* без разрешения VPN работает, просто без видимого уведомления */ }

    // При старте: /status → (404 → /register) → подписка и срок
    LaunchedEffect(Unit) {
        vm.refresh()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Возврат из браузера после оплаты / из фона
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                UiEvent.RequestVpnPermission -> {
                    val intent = runCatching { VpnService.prepare(ctx) }.getOrNull()
                    if (intent == null) vm.startVpnService() else vpnLauncher.launch(intent)
                }
                is UiEvent.OpenPayment -> if (!openInBrowser(ctx, event.url)) vm.onPaymentOpenFailed()
                is UiEvent.OpenAuth -> if (!openInBrowser(ctx, event.url)) vm.onAuthOpenFailed()
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.auth == AuthState.SignedOut -> LoginPanel(state.error, onSignIn = vm::signIn)
                state.access == Access.Unknown -> LoadError(state.error, onRetry = vm::refresh)
                else -> VpnPanel(state, vm)
            }
        }
    }
}

@Composable
private fun LoginPanel(error: String?, onSignIn: () -> Unit) {
    Spacer(Modifier.height(96.dp))
    Text("Вход", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(
        "Войдите через Яндекс ID: подписка привязывается к аккаунту, а не к устройству.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Войти через Яндекс") }
}

@Composable
private fun LoadError(message: String?, onRetry: () -> Unit) {
    Spacer(Modifier.height(96.dp))
    Text(
        message ?: "Не удалось загрузить данные",
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text("Повторить") }
}

@Composable
private fun VpnPanel(state: MainUiState, vm: MainViewModel) {
    val active = state.access is Access.Active
    val expired = state.access is Access.Expired

    // Подключаться можно только с активной подписки; отключаться — всегда.
    val connecting = state.vpnStatus == VpnStatus.CONNECTING
    val canToggle = !state.busy && !connecting &&
        (state.vpnStatus == VpnStatus.CONNECTED || active)

    val (label, color) = when {
        state.vpnStatus == VpnStatus.CONNECTED -> "Подключено" to Color(0xFF2E7D32)
        connecting -> "Подключение…" to Color(0xFFF9A825)
        expired -> "Отключено" to Color(0xFF9E9E9E)
        else -> "Отключено" to Color(0xFF546E7A)
    }

    Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
    Spacer(Modifier.height(32.dp))

    // Большая круглая кнопка Toggle (enabled = false при истёкшей подписке)
    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(enabled = canToggle, onClick = vm::onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (state.busy || connecting) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Text(
                text = if (state.vpnStatus == VpnStatus.CONNECTED) "Отключить" else "Подключить",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    (state.access as? Access.Active)?.let {
        Text("Подписка активна до ${formatDate(it.expiryTime)}", style = MaterialTheme.typography.bodyMedium)
    }

    if (state.awaitingPayment) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.info?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }

    Spacer(Modifier.height(24.dp))
    if (expired || state.showPlans) {
        PlansCard(state, expired, vm)
    } else {
        TextButton(onClick = vm::toggleShowPlans) { Text("Продлить подписку") }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = vm::refresh) { Text("Обновить") }

    (state.auth as? AuthState.SignedIn)?.let { a ->
        Spacer(Modifier.height(16.dp))
        a.login?.let { Text("Аккаунт: $it", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = vm::signOut) { Text("Выйти") }
    }
}

@Composable
private fun PlansCard(state: MainUiState, expired: Boolean, vm: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (expired) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (expired) "Срок действия подписки истек" else "Продление подписки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            Plan.entries.forEach { plan ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.selectedPlan == plan,
                            onClick = { vm.selectPlan(plan) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    RadioButton(selected = state.selectedPlan == plan, onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Text("${plan.label} — ${plan.priceRub} ₽")
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = vm::renew,
                enabled = !state.busy && !state.awaitingPayment,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Продлить") }

            if (!expired) {
                TextButton(onClick = vm::toggleShowPlans, modifier = Modifier.align(Alignment.End)) {
                    Text("Скрыть")
                }
            }
        }
    }
}

/** @return false, если открыть страницу оплаты не удалось (нет браузера). */
private fun openInBrowser(ctx: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    return try {
        CustomTabsIntent.Builder().build().launchUrl(ctx, uri)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormatter)
