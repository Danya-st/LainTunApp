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
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2rayez.app.R
import com.v2rayez.app.data.api.Plan
import com.v2rayez.app.data.service.VpnStatus
import com.v2rayez.app.ui.components.PowerButton
import com.v2rayez.app.ui.components.PowerGlyph
import com.v2rayez.app.ui.components.TypewriterText
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.LocalReduceMotion
import com.v2rayez.app.ui.theme.SurfaceElevated2
import com.v2rayez.app.ui.theme.TextPrimary
import com.v2rayez.app.ui.theme.TextSecondary
import com.v2rayez.app.ui.theme.TextTertiary
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.theme.accentGradient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class Screen { Loading, Login, Error, Main }

/** Терминал статуса всегда тёмный (и в светлой теме) — как окно консоли. */
private val TerminalBg = Color(0xFF0A0E17)

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

    val reduceMotion = rememberReduceMotion()
    val screen = when {
        state.loading -> Screen.Loading
        state.auth == AuthState.SignedOut -> Screen.Login
        state.access == Access.Unknown -> Screen.Error
        else -> Screen.Main
    }
    val primary = MaterialTheme.colorScheme.primary
    val glow = when {
        screen != Screen.Main -> primary
        state.vpnStatus == VpnStatus.CONNECTED -> Connected
        state.vpnStatus == VpnStatus.CONNECTING -> Warning
        else -> primary
    }

    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppBackground(glow) {
                BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                    val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                    val rawSize = if (wide) {
                        minOf(maxWidth * 0.3f, maxHeight * 0.5f, 240.dp)
                    } else {
                        minOf(maxWidth * 0.55f, maxHeight * 0.34f, 220.dp)
                    }
                    val buttonSize = rawSize.coerceAtLeast(140.dp)

                    AnimatedContent(
                        targetState = screen,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                            else fadeIn(tween(280)) togetherWith fadeOut(tween(160))
                        },
                        label = "screen"
                    ) { s ->
                        when (s) {
                            Screen.Loading -> LoadingScreen()
                            Screen.Login -> LoginScreen(state.error, onSignIn = vm::signIn)
                            Screen.Error -> ErrorScreen(state.error, onRetry = vm::refresh)
                            Screen.Main -> MainScreen(state, vm, wide, buttonSize)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── каркас ─────────────────────────

@Composable
private fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Фон с мягким свечением сверху; цвет свечения плавно меняется со статусом подключения. */
@Composable
private fun AppBackground(glow: Color, content: @Composable () -> Unit) {
    val animated by animateColorAsState(glow, tween(600), label = "glow")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(animated.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.28f),
                        radius = size.maxDimension * 0.7f
                    )
                )
            }
    ) { content() }
}

/** Прокручиваемая колонка по центру с ограничением ширины; на коротком контенте — центрируется по вертикали. */
@Composable
private fun ScreenColumn(
    maxContentWidth: Dp = 480.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = verticalArrangement,
                content = content
            )
        }
    }
}

// ───────────────────────── загрузка / вход / ошибка ─────────────────────────

@Composable
private fun LoadingScreen() {
    ScreenColumn {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        TypewriterText(
            text = "> загрузка",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            cursor = true
        )
    }
}

@Composable
private fun LoginScreen(error: String?, onSignIn: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    ScreenColumn {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(accentGradient(primary))),
            contentAlignment = Alignment.Center
        ) { PowerGlyph(Modifier.size(40.dp), Color.White) }

        Spacer(Modifier.height(28.dp))
        Text(
            "Вход",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Войдите через Яндекс ID.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        error?.let {
            Spacer(Modifier.height(20.dp))
            ErrorBanner(it)
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background
            )
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFFC3F1D)),
                contentAlignment = Alignment.Center
            ) { Text("Я", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.size(12.dp))
            Text("Войти через Яндекс", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ErrorScreen(message: String?, onRetry: () -> Unit) {
    ScreenColumn {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(ErrorRed.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Text("!", color = ErrorRed, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
        Text(
            message ?: "Не удалось загрузить данные",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, shape = MaterialTheme.shapes.large) { Text("Повторить") }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = ErrorRed.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.35f))
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

// ───────────────────────── главный экран ─────────────────────────

@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, wide: Boolean, buttonSize: Dp) {
    ScreenColumn(
        maxContentWidth = if (wide) 960.dp else 480.dp,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = vm::refresh) { Text("Обновить") }
        }

        if (wide) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Hero(state, vm, buttonSize)
                }
                Column(Modifier.weight(1f)) { Details(state, vm) }
            }
        } else {
            Hero(state, vm, buttonSize)
            Spacer(Modifier.height(24.dp))
            Details(state, vm)
        }
    }
}

@Composable
private fun Hero(state: MainUiState, vm: MainViewModel, buttonSize: Dp) {
    val active = state.access is Access.Active
    val expired = state.access is Access.Expired
    val connected = state.vpnStatus == VpnStatus.CONNECTED
    val connecting = state.vpnStatus == VpnStatus.CONNECTING

    // Подключаться можно только с активной подписки; отключаться — всегда.
    val canToggle = !state.busy && !connecting && (connected || active)

    val tone = when {
        connected -> Connected
        connecting -> Warning
        canToggle -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val haptic = LocalHapticFeedback.current

    PowerButton(
        color = tone,
        connected = connected,
        working = state.busy || connecting,
        enabled = canToggle,
        diameter = buttonSize,
        description = if (connected) "Отключить VPN" else "Подключить VPN",
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.onToggle()
        }
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = when {
            connected -> "Нажмите, чтобы отключить"
            state.busy || connecting -> "Подождите…"
            canToggle -> "Нажмите, чтобы подключить"
            else -> "Продлите подписку, чтобы подключиться"
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))

    val label = when {
        connected -> "Подключено"
        connecting -> "Подключение…"
        else -> "Отключено"
    }
    val labelColor = when {
        connected -> Connected
        connecting -> Warning
        else -> TextPrimary
    }
    val lines = buildList<TermLine> {
        add(TermLine("status", "> ", label, labelColor))
        when (val a = state.access) {
            is Access.Active -> add(TermLine("sub", "# ", "подписка активна до ${formatDate(a.expiryTime)}", TextSecondary))
            is Access.Expired -> add(TermLine("sub", "# ", "подписка истекла", ErrorRed))
            Access.Unknown -> Unit
        }
        state.info?.let { add(TermLine("info", "$ ", it, TextSecondary)) }
        state.error?.let { add(TermLine("error", "! ", it, ErrorRed)) }
    }
    StatusTerminal(lines, awaiting = state.awaitingPayment)
}

private data class TermLine(val id: String, val prefix: String, val text: String, val color: Color)

/**
 * Окно «терминала» со статусами. Строки печатаются по очереди (следующая — когда допечатана предыдущая),
 * курсор мигает на последней строке. Изменившийся статус перепечатывается заново.
 */
@Composable
private fun StatusTerminal(lines: List<TermLine>, awaiting: Boolean) {
    var finished by remember { mutableIntStateOf(0) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = TerminalBg,
        border = BorderStroke(1.dp, SurfaceElevated2)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(ErrorRed, Warning, Connected).forEach {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(it.copy(alpha = 0.8f)))
                    Spacer(Modifier.size(6.dp))
                }
                Spacer(Modifier.size(4.dp))
                Text("status", style = MaterialTheme.typography.labelSmall, color = TextTertiary, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(14.dp))

            lines.forEachIndexed { i, line ->
                if (i <= finished) key(line.id) {
                    val style = if (i == 0) {
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    }
                    Row(Modifier.padding(top = if (i == 0) 0.dp else 6.dp)) {
                        Text(line.prefix, style = style, color = TextTertiary, fontFamily = FontFamily.Monospace)
                        TypewriterText(
                            text = line.text,
                            modifier = Modifier.weight(1f),
                            style = style,
                            color = line.color,
                            cursor = i == lines.lastIndex,
                            onFinished = { if (finished < i + 1) finished = i + 1 }
                        )
                    }
                }
            }

            if (awaiting) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = Warning,
                    trackColor = SurfaceElevated2
                )
            }
        }
    }
}

// ───────────────────────── подписка / тарифы / аккаунт ─────────────────────────

@Composable
private fun Details(state: MainUiState, vm: MainViewModel) {
    val expired = state.access is Access.Expired
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            expired || state.showPlans -> PlansCard(state, expired, vm)
            state.access is Access.Active -> SubscriptionCard(
                expiryTime = state.access.expiryTime,
                onExtend = vm::toggleShowPlans
            )
        }
        (state.auth as? AuthState.SignedIn)?.let { AccountRow(it.login, onSignOut = vm::signOut) }
    }
}

@Composable
private fun SubscriptionCard(expiryTime: Long, onExtend: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Осталось", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatRemaining(expiryTime, System.currentTimeMillis()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(onClick = onExtend, shape = MaterialTheme.shapes.medium) { Text("Продлить") }
        }
    }
}

@Composable
private fun PlansCard(state: MainUiState, expired: Boolean, vm: MainViewModel) {
    val error = MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = if (expired) error.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (expired) BorderStroke(1.dp, error.copy(alpha = 0.35f)) else null
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (expired) "Срок действия подписки истек" else "Продление подписки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Plan.entries.forEach { plan ->
                    PlanTile(plan, selected = state.selectedPlan == plan, onSelect = { vm.selectPlan(plan) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = vm::renew,
                enabled = !state.busy && !state.awaitingPayment,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large
            ) { Text("Продлить · ${state.selectedPlan.priceRub} ₽", fontWeight = FontWeight.SemiBold) }

            if (!expired) {
                TextButton(onClick = vm::toggleShowPlans, modifier = Modifier.align(Alignment.End)) {
                    Text("Скрыть")
                }
            }
        }
    }
}

private val Plan.months: Int get() = when (this) { Plan.M1 -> 1; Plan.M2 -> 2; Plan.M3 -> 3 }

@Composable
private fun PlanTile(plan: Plan, selected: Boolean, onSelect: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = MaterialTheme.shapes.medium
    val borderColor by animateColorAsState(if (selected) primary else MaterialTheme.colorScheme.outline, tween(180), label = "tileBorder")
    val bg by animateColorAsState(if (selected) primary.copy(alpha = 0.12f) else Color.Transparent, tween(180), label = "tileBg")
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, tween(180), label = "tileDot")
    val saving = (100 * (1 - plan.priceRub.toDouble() / (Plan.M1.priceRub * plan.months))).roundToInt()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(2.dp, borderColor, shape)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).border(2.dp, borderColor, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot).clip(CircleShape).background(primary))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plan.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (saving > 0) {
                    Spacer(Modifier.size(8.dp))
                    Surface(shape = CircleShape, color = Connected.copy(alpha = 0.18f)) {
                        Text(
                            "−$saving%",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Connected,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (plan.months > 1) {
                Text(
                    "≈ ${plan.priceRub / plan.months} ₽ в месяц",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text("${plan.priceRub} ₽", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccountRow(login: String?, onSignOut: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    login?.firstOrNull()?.uppercase() ?: "Я",
                    color = primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Аккаунт", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    login ?: "Яндекс ID",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onSignOut) { Text("Выйти") }
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
