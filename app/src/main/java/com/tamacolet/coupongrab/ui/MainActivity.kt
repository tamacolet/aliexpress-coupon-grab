package com.tamacolet.coupongrab.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tamacolet.coupongrab.accessibility.AccessibilityWatch
import com.tamacolet.coupongrab.core.ALI_PACKAGE
import com.tamacolet.coupongrab.core.ScheduleDeletion
import com.tamacolet.coupongrab.core.AppState
import com.tamacolet.coupongrab.core.ClockSource
import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.CodeValidation
import com.tamacolet.coupongrab.core.Schedule
import com.tamacolet.coupongrab.core.ScheduleKind
import com.tamacolet.coupongrab.service.Notifier
import com.tamacolet.coupongrab.service.AlarmScheduler
import com.tamacolet.coupongrab.service.app
import com.tamacolet.coupongrab.time.NetworkClockSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val jst: ZoneId = ZoneId.of("Asia/Tokyo")
private val clockMeasureFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val historyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss.SSS", Locale.JAPAN)
private const val busyMessage = "実行中は編集できません。完了後にもう一度お試しください"

private data class EditorSession(val draft: ScheduleEditorDraft, val existing: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val uiState = mutableStateOf(AppState())
    private val permissionRevision = mutableIntStateOf(0)
    private val clockMeasuring = mutableStateOf(false)
    private val clockError = mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            MaterialTheme {
                var now by remember { mutableLongStateOf(correctedNow(uiState.value)) }
                var editor by remember { mutableStateOf<EditorSession?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    while (true) {
                        val state = app.store.load()
                        uiState.value = state
                        now = correctedNow(state)
                        permissionRevision.intValue++
                        delay(1_000)
                    }
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(if (uiState.value.busy) "アリエク券取り（実行中）" else "アリエク券取り") })
                }) { padding ->
                    LazyColumn(
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    ) {
                        item { SecurityWarning() }
                        item { AccessibilityWarning() }
                        item {
                            ClockCard(uiState.value, now, clockMeasuring.value, clockError.value) { measureClock(scope) }
                        }
                        item { PermissionCard() }
                        item {
                            Button(
                                onClick = { editor = EditorSession(ScheduleEditorRules.newDraft(now), existing = false) },
                                enabled = !uiState.value.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("予定を追加") }
                        }
                        items(uiState.value.schedules, key = { it.id }) { schedule ->
                            ScheduleCard(schedule, now, uiState.value.busy) {
                                editor = EditorSession(ScheduleEditorRules.fromSchedule(schedule), existing = true)
                            }
                        }
                        item { Text("実行履歴（新しい順・100件まで）", style = MaterialTheme.typography.titleMedium) }
                        items(uiState.value.history.takeLast(100).asReversed()) { entry ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${historyFormat.format(Instant.ofEpochMilli(entry.atMs).atZone(jst))} ${historyLabel(entry.event)}")
                                    if (entry.code.isNotEmpty()) Text("コード: ${entry.code}", style = MaterialTheme.typography.bodySmall)
                                    if (entry.detail.isNotEmpty()) Text("結果: ${historyDetail(entry.detail)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                editor?.let { session ->
                    ScheduleEditorDialog(
                        initial = session.draft,
                        existing = session.existing,
                        busy = uiState.value.busy,
                        onDismiss = { editor = null },
                        onSave = { saveSchedule(it, session.existing) },
                        onDelete = ::deleteSchedule,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        uiState.value = app.store.load()
        permissionRevision.intValue++
        AccessibilityWatch.observeConnection(this)
    }

    /** 予定の実行前と同じ測り方で、今すぐ時計のずれを測る。券取りの実行中は測らない。 */
    private fun measureClock(scope: CoroutineScope) {
        if (clockMeasuring.value || app.store.load().busy) return
        clockMeasuring.value = true
        clockError.value = null
        scope.launch {
            try {
                val correction = NetworkClockSync().measure()
                uiState.value = app.store.update { if (it.busy) it else it.copy(correction = correction) }
                if (correction.source == ClockSource.NONE) {
                    clockError.value = "ネットの時計に届きませんでした。通信を確かめて、もう一度押してください"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clockError.value = "測れませんでした（${error.javaClass.simpleName}）"
            } finally {
                clockMeasuring.value = false
            }
        }
    }

    private fun saveSchedule(draft: ScheduleEditorDraft, existing: Boolean): String? {
        val initial = app.store.load()
        if (initial.busy) return busyMessage
        return try {
            val persisted = initial.schedules.firstOrNull { it.id == draft.id }
            require(!existing || persisted != null) { "この予定は削除されています" }
            ScheduleEditorRules.build(draft, persisted, correctedNow(initial))
            val updated = app.store.update { state ->
                require(!state.busy) { busyMessage }
                val latest = state.schedules.firstOrNull { it.id == draft.id }
                require(!existing || latest != null) { "この予定は削除されています" }
                val saved = ScheduleEditorRules.build(draft, latest, correctedNow(state))
                state.copy(
                    pendingRuns = state.pendingRuns.filterNot { it.scheduleId == draft.id },
                    schedules = if (latest == null) state.schedules + saved else state.schedules.map {
                        if (it.id == saved.id) saved else it
                    },
                )
            }
            AlarmScheduler.schedule(this, updated.schedules.first { it.id == draft.id })
            uiState.value = updated
            null
        } catch (error: IllegalArgumentException) {
            error.message ?: "入力内容を確認してください"
        }
    }

    private fun deleteSchedule(scheduleId: String, confirmed: Boolean) {
        var deleted = false
        val updated = app.store.update { state ->
            ScheduleDeletion.apply(state, scheduleId, confirmed).also {
                deleted = state.schedules.any { it.id == scheduleId } && it.schedules.none { it.id == scheduleId }
            }
        }
        if (deleted) AlarmScheduler.cancel(this, scheduleId)
        uiState.value = updated
    }

    @Composable
    private fun SecurityWarning() {
        if (uiState.value.securityHoldSinceMs == null) return
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("アリエクの安全確認が出たので止めています。アリエクでスライダーを手で解いてから再開してください",
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = {
                    uiState.value = app.store.update { it.copy(securityHoldSinceMs = null) }
                    Notifier(this@MainActivity).clearSecurityHold()
                    AlarmScheduler.scheduleAll(this@MainActivity)
                }) { Text("再開") }
                TextButton(onClick = {
                    packageManager.getLaunchIntentForPackage(ALI_PACKAGE)?.let {
                        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(it)
                    }
                }) { Text("アリエクを開く") }
            }
        }
    }

    @Composable
    private fun AccessibilityWarning() {
        permissionRevision.intValue
        if (AccessibilityWatch.status(this) == AccessibilityWatch.Status.CONNECTED) return
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ユーザー補助が外れています。このままでは予約の時刻に券を取れません",
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = { startActivity(AccessibilityWatch.settingsIntent(this@MainActivity)) }) {
                    Text("付け直す")
                }
                Text("行が灰色で押せない時: アプリ情報 →右上︙→「制限付き設定を許可」",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { startActivity(AccessibilityWatch.appInfoIntent(this@MainActivity)) }) {
                    Text("アプリ情報を開く")
                }
            }
        }
    }

    @Composable
    private fun PermissionCard() {
        permissionRevision.intValue
        val accessibility = AccessibilityWatch.status(this)
        val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val runtimeNotification = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notifications = runtimeNotification && NotificationManagerCompat.from(this).areNotificationsEnabled()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("実行に必要な設定", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ユーザー補助", Modifier.weight(1f))
                    Text(accessibility.label, Modifier.weight(2f))
                    if (accessibility != AccessibilityWatch.Status.CONNECTED) {
                        OutlinedButton(onClick = { startActivity(AccessibilityWatch.settingsIntent(this@MainActivity)) }) {
                            Text("設定")
                        }
                    }
                }
                PermissionRow("正確なアラーム", exact) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
                PermissionRow("通知", notifications, ::requestOrOpenNotificationSettings)
            }
        }
    }

    private fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = getPreferences(MODE_PRIVATE)
            val requested = preferences.getBoolean("notification_permission_requested", false)
            if (!requested || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                preferences.edit().putBoolean("notification_permission_requested", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, open: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f))
            Text(if (granted) "許可済み" else "未許可")
            if (!granted) OutlinedButton(onClick = open) { Text("設定") }
        }
    }
}

@Composable
private fun ClockCard(state: AppState, now: Long, measuring: Boolean, error: String?, onMeasure: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val correction = state.correction
            val measured = correction.measuredAtMs > 0L
            when {
                measuring -> {
                    Text("時計のずれ 測定中…", style = MaterialTheme.typography.titleMedium)
                    Text("ネットの時計と比べています（最大12秒）", style = MaterialTheme.typography.bodySmall)
                }
                !measured -> {
                    Text("時計のずれ 未測定", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "券を取る時は、直前に毎回ネットの時計と比べ、ずれを自動で直した時刻で「適用」を押します。今のずれを見たい時は「今すぐ測る」を押してください",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    val at = clockMeasureFormat.format(Instant.ofEpochMilli(correction.measuredAtMs).atZone(jst))
                    val drift = String.format(Locale.JAPAN, "%+.2f", -correction.offsetMs / 1_000.0)
                    val direction = when {
                        correction.offsetMs < 0 -> "スマホが進んでいる・"
                        correction.offsetMs > 0 -> "スマホが遅れている・"
                        else -> ""
                    }
                    Text("時計のずれ ${drift}秒（$direction$at 測定）", style = MaterialTheme.typography.titleMedium)
                    Text("測定方式: ${clockSourceLabel(correction.source)}")
                    Text("券を取る時は直前に毎回測り直し、ずれを自動で直した時刻で「適用」を押します", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onMeasure, enabled = !measuring && !state.busy) {
                Text(if (measured) "測り直す" else "今すぐ測る")
            }
            if (state.busy) Text("券取りの実行中は測れません", style = MaterialTheme.typography.bodySmall)
            if (state.nasBusyUntilMs > now) Text("NAS側が実行中のため、繰り返し予定は待機します")
        }
    }
}

@Composable
private fun ScheduleCard(schedule: Schedule, now: Long, busy: Boolean, edit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (schedule.kind == ScheduleKind.EXACT) "時刻指定" else "繰り返し",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (schedule.kind == ScheduleKind.EXACT) {
                    schedule.exactAtMs?.let { "${ScheduleEditorRules.formatExactAtMs(it)} JST" } ?: "日時未設定"
                } else "${schedule.repeatMinutes}分間隔",
            )
            schedule.codes.forEach { code ->
                Row {
                    Text(code.code, Modifier.weight(1f))
                    Text(statusLabel(code.status))
                }
            }
            HorizontalDivider()
            Text(nextRunLabel(schedule, now))
            OutlinedButton(onClick = edit, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("編集") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorDialog(
    initial: ScheduleEditorDraft,
    existing: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleEditorDraft) -> String?,
    onDelete: (String, Boolean) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }
    var attempted by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing) "予定を編集" else "予定を追加", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                    if (existing) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            enabled = !busy,
                        ) { Text("削除") }
                    }
                    Button(
                        onClick = {
                            attempted = true
                            val message = onSave(draft)
                            if (message == null) onDismiss() else error = message
                        },
                        enabled = !busy,
                    ) { Text("保存") }
                }
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (busy) item { Text(busyMessage, color = MaterialTheme.colorScheme.error) }
                    item {
                        Text("種類")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.kind == ScheduleKind.EXACT,
                                onClick = { draft = draft.copy(kind = ScheduleKind.EXACT) },
                                label = { Text("時刻指定") },
                                enabled = !busy,
                            )
                            FilterChip(
                                selected = draft.kind == ScheduleKind.REPEAT,
                                onClick = { draft = draft.copy(kind = ScheduleKind.REPEAT) },
                                label = { Text("繰り返し") },
                                enabled = !busy,
                            )
                        }
                    }
                    if (draft.kind == ScheduleKind.EXACT) {
                        item {
                            OutlinedTextField(
                                value = draft.exactAtText,
                                onValueChange = { draft = draft.copy(exactAtText = it) },
                                label = { Text("日時（JST）") },
                                supportingText = { Text("YYYY-MM-DD HH:mm:ss（日本時間）") },
                                isError = attempted && ScheduleEditorRules.parseExactAtMs(draft.exactAtText) == null,
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        item {
                            OutlinedTextField(
                                value = draft.repeatMinutesText,
                                onValueChange = { draft = draft.copy(repeatMinutesText = it) },
                                label = { Text("間隔（分・1〜60）") },
                                isError = attempted && draft.repeatMinutesText.toIntOrNull()?.let { it in 1..60 } != true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = draft.codesText,
                            onValueChange = { draft = draft.copy(codesText = it) },
                            label = { Text("コード（1行に1つ）") },
                            supportingText = { Text("ASCII英数字3〜32文字") },
                            isError = attempted && runCatching { CodeValidation.parseMultiline(draft.codesText) }.isFailure,
                            minLines = 4,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("有効にする", Modifier.weight(1f))
                            Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) }, enabled = !busy)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この予定を消しますか") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    onDelete(draft.id, true)
                    confirmDelete = false
                    onDismiss()
                }) { Text("消す") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

private fun correctedNow(state: AppState): Long {
    val correction = state.correction
    val elapsed = SystemClock.elapsedRealtime()
    return if (correction.measuredAtMs > 0 && correction.anchorElapsedMs > 0 && elapsed >= correction.anchorElapsedMs) {
        correction.now(elapsed)
    } else System.currentTimeMillis()
}

private fun nextRunLabel(schedule: Schedule, now: Long): String {
    if (!schedule.enabled || schedule.nextRunAtMs <= 0) return "次回実行: なし（無効）"
    val date = ScheduleEditorRules.formatExactAtMs(schedule.nextRunAtMs)
    val seconds = ((schedule.nextRunAtMs - now).coerceAtLeast(0) + 999) / 1_000
    return "次回実行: $date JST\nあと${seconds}秒"
}

private fun statusLabel(status: CodeStatus): String = when (status) {
    CodeStatus.WAITING -> "待ち"
    CodeStatus.APPLIED -> "取れた"
    CodeStatus.USED -> "使用済み"
    CodeStatus.FAILURE -> "失敗"
}

private fun clockSourceLabel(source: ClockSource): String = when (source) {
    ClockSource.SNTP -> "SNTP"
    ClockSource.HTTP_DATE -> "HTTP（秒精度）"
    ClockSource.NONE -> "測れず（スマホの時計のまま）"
}

private fun historyLabel(event: String): String = when (event) {
    "apply" -> "適用を試行"
    "apply_retry" -> "適用を再試行"
    "code_error" -> "画面操作に失敗"
    "repeat_preempted" -> "予約を先に実行"
    "security_hold" -> "安全確認のため停止"
    "security_hold_skip" -> "安全確認のため予定を見送り"
    "code_applied" -> "クーポンを取得"
    "code_used" -> "使用済み"
    "code_failure" -> "コードの取得に失敗"
    "clock" -> "時刻を測定"
    "nas_defer" -> "NAS待ちのため延期"
    "run_complete" -> "実行完了"
    "cleanup_failure" -> "後片付けに失敗"
    else -> "その他（$event）"
}

private fun historyDetail(detail: String): String = when (detail) {
    "APPLIED" -> "取れた"
    "USED" -> "使用済み"
    "FAILURE" -> "失敗"
    "WAITING" -> "待ち"
    else -> detail
}
