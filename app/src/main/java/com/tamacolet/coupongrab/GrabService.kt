package com.tamacolet.coupongrab

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.tamacolet.coupongrab.accessibility.AccessibilityWatch
import com.tamacolet.coupongrab.automation.CouponAutomation
import com.tamacolet.coupongrab.automation.ExactPreemptionException
import com.tamacolet.coupongrab.core.AutomationFlow
import com.tamacolet.coupongrab.core.SecurityCheckException
import com.tamacolet.coupongrab.core.SECURITY_CHECK_REASON
import com.tamacolet.coupongrab.core.RunAdmission
import com.tamacolet.coupongrab.core.AppState
import com.tamacolet.coupongrab.core.ClockSource
import com.tamacolet.coupongrab.core.CodeStatus
import com.tamacolet.coupongrab.core.ExecutionPolicy
import com.tamacolet.coupongrab.core.HistoryEntry
import com.tamacolet.coupongrab.core.HistoryRules
import com.tamacolet.coupongrab.core.RunFailureReducer
import com.tamacolet.coupongrab.core.RunQueue
import com.tamacolet.coupongrab.core.RunRequest
import com.tamacolet.coupongrab.core.ScheduleKind
import com.tamacolet.coupongrab.core.ScheduleRules
import com.tamacolet.coupongrab.core.StatusReporter
import com.tamacolet.coupongrab.service.AlarmScheduler
import com.tamacolet.coupongrab.service.Notifier
import com.tamacolet.coupongrab.service.WakeActivity
import com.tamacolet.coupongrab.service.app
import com.tamacolet.coupongrab.time.NetworkClockSync
import com.tamacolet.coupongrab.time.systemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.FileDescriptor
import java.io.PrintWriter

class GrabService : Service() {
    private lateinit var notifier: Notifier
    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var draining = false

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "coupongrab:apply",
        )
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifier.RUNNING_ID, notifier.running(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifier.RUNNING_ID, notifier.running())
        }
        app.store.update { it.copy(busy = false) }
        if (app.store.load().pendingRuns.isNotEmpty()) drain()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN) {
            val id = intent.getStringExtra(EXTRA_SCHEDULE_ID)
            val kind = runCatching {
                ScheduleKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty())
            }.getOrDefault(ScheduleKind.REPEAT)
            if (id != null) {
                val request = RunRequest(id, kind)
                app.store.update { it.copy(pendingRuns = RunQueue.enqueue(it.pendingRuns, request)) }
                drain()
            }
        }
        if (intent == null) drain()
        // 実行要求は JSON にあるため、null intent での再作成時も drain できる。
        return START_STICKY
    }

    private fun drain() {
        if (draining) return
        draining = true
        scope.launch {
            while (true) {
                val request = RunQueue.next(app.store.load().pendingRuns)
                if (request == null) {
                    draining = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                val disposition = runRequest(request)
                if (disposition == RunDisposition.REMOVE) {
                    app.store.update {
                        it.copy(pendingRuns = RunQueue.remove(it.pendingRuns, request))
                    }
                }
            }
        }
    }

    private suspend fun runRequest(request: RunRequest): RunDisposition {
        val initial = app.store.load()
        val schedule = initial.schedules.firstOrNull { it.id == request.scheduleId && it.enabled }
            ?: return RunDisposition.REMOVE
        val now = correctedNow(initial)
        if (initial.securityHoldSinceMs != null) {
            app.store.updateWithResult { RunAdmission.claim(it, request, correctedNow(it), SystemClock.elapsedRealtime()) }
            if (schedule.kind == ScheduleKind.REPEAT) {
                app.store.load().schedules.firstOrNull { it.id == schedule.id }?.let { AlarmScheduler.schedule(this, it) }
            }
            return RunDisposition.REMOVE
        }
        if (!ExecutionPolicy.accepts(schedule, request, now)) {
            AlarmScheduler.schedule(this, schedule)
            return RunDisposition.REMOVE
        }
        if (ExecutionPolicy.isStaleExact(schedule, now)) {
            val message = "時刻指定の実行が遅延しすぎたため中止しました"
            failLatestPending(schedule.id, message)
            finalizeSchedule(schedule.id, message)
            return RunDisposition.REMOVE
        }
        val decision = app.store.updateWithResult { state ->
            RunAdmission.claim(state, request, correctedNow(state), SystemClock.elapsedRealtime())
        }
        when (decision) {
            is ScheduleRules.NasDecision.Defer -> {
                record("nas_defer", schedule.id, "", "delayMs=${decision.delayMs}")
                app.store.load().schedules.firstOrNull { it.id == schedule.id }
                    ?.let { AlarmScheduler.schedule(this, it) }
                return RunDisposition.REMOVE
            }
            ScheduleRules.NasDecision.SecurityHold -> return RunDisposition.REMOVE
            ScheduleRules.NasDecision.Execute -> Unit
            null -> return RunDisposition.REMOVE
        }

        var failure: String? = null
        var finalize = false
        var preempted = false
        AutomationFlow.withCleanup(action = {
            try {
                if (schedule.kind == ScheduleKind.REPEAT) AccessibilityWatch.check(this, schedule)
                else AccessibilityWatch.observeConnection(this)
                wakeLock.acquire(ExecutionPolicy.MAX_EXECUTION_MS)
                withTimeout(ExecutionPolicy.MAX_EXECUTION_MS) {
                    val correction = NetworkClockSync().measure()
                    app.store.update { it.copy(correction = correction) }
                    if (correction.source == ClockSource.NONE) notifier.clockFallback()
                    record(
                        "clock", schedule.id, "",
                        "source=${correction.source} offsetMs=${correction.offsetMs} rttMs=${correction.rttMs}",
                    )
                    val clock = systemClock(correction)
                    val latest = app.store.load().schedules.firstOrNull { it.id == schedule.id && it.enabled }
                    if (latest != null) {
                        val targets = ScheduleRules.pendingCodes(latest)
                        if (targets.isNotEmpty()) {
                            CouponAutomation(
                                context = this@GrabService,
                                clock = clock,
                                onEvent = { event, code, detail -> record(event, latest.id, code, detail) },
                                onCode = { code, status, detail -> updateCode(latest.id, code, status, detail) },
                                shouldYieldForExact = {
                                    latest.kind == ScheduleKind.REPEAT &&
                                        app.store.load().pendingRuns.any { it.kind == ScheduleKind.EXACT }
                                },
                            ).execute(targets, latest.nextRunAtMs)
                        }
                    }
                }
                finalize = true
            } catch (_: ExactPreemptionException) {
                // 繰り返し要求はキューに残し、次の選択で時刻指定を必ず先に実行する。
                preempted = true
                record("repeat_preempted", schedule.id, "", "exact queued")
            } catch (_: SecurityCheckException) {
                app.store.update { it.copy(securityHoldSinceMs = it.securityHoldSinceMs ?: correctedNow(it)) }
                record("security_hold", schedule.id, "", SECURITY_CHECK_REASON)
                notifier.securityHold()
            } catch (_: TimeoutCancellationException) {
                failure = "実行時間の上限を超えました"
                failLatestPending(schedule.id, checkNotNull(failure))
                finalize = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.simpleName
                // 初期スナップショットではなく最新の pending だけを失敗にし、成功を後退させない。
                failLatestPending(schedule.id, checkNotNull(failure))
                finalize = true
            } finally {
                AccessibilityWatch.observeConnection(this)
                if (finalize) finalizeSchedule(schedule.id, failure)
            }
        }, cleanup = {
            app.store.update { it.copy(busy = false) }
            if (wakeLock.isHeld) wakeLock.release()
        })
        return if (preempted) RunDisposition.KEEP else RunDisposition.REMOVE
    }

    private fun failLatestPending(scheduleId: String, detail: String) {
        val pending = app.store.updateWithResult { state ->
            val updated = RunFailureReducer.apply(state, scheduleId, detail)
            val previous = state.schedules.firstOrNull { it.id == scheduleId }?.codes.orEmpty()
            val changed = updated.schedules.firstOrNull { it.id == scheduleId }?.codes.orEmpty().filter { code ->
                previous.any { it.code == code.code && AutomationFlow.statusChanged(it.status, code.status) }
            }
            updated to changed
        }
        pending.forEach {
            record("code_failure", scheduleId, it.code, detail)
            notifier.codeResult(it.code, CodeStatus.FAILURE, detail)
        }
    }

    private fun finalizeSchedule(scheduleId: String, failure: String?) {
        val now = correctedNow(app.store.load())
        Log.i("CouponGrab", "run_complete schedule=$scheduleId failure=${failure.orEmpty()}")
        val finalState = app.store.update { state ->
            val current = state.schedules.firstOrNull { it.id == scheduleId }
            val updated = current?.let { ScheduleRules.afterRun(it, now) }
            state.copy(
                schedules = if (updated == null) state.schedules else state.schedules.map {
                    if (it.id == updated.id) updated else it
                },
                busy = false,
            )
        }
        finalState.schedules.firstOrNull { it.id == scheduleId }?.let { AlarmScheduler.schedule(this, it) }
    }

    private fun updateCode(scheduleId: String, code: String, status: CodeStatus, detail: String) {
        val changed = app.store.updateWithResult { state ->
            val previous = state.schedules.firstOrNull { it.id == scheduleId }?.codes?.firstOrNull { it.code == code }
            val updated = state.copy(schedules = state.schedules.map { schedule ->
                if (schedule.id != scheduleId) schedule else schedule.copy(codes = schedule.codes.map {
                    if (it.code == code) it.copy(status = status, detail = detail) else it
                })
            })
            updated to (previous != null && AutomationFlow.statusChanged(previous.status, status))
        }
        record("code_${status.name.lowercase()}", scheduleId, code, detail)
        if (changed) notifier.codeResult(code, status, detail)
    }

    private fun record(event: String, scheduleId: String, code: String, detail: String) {
        app.store.update { state ->
            val now = correctedNow(state)
            Log.i("CouponGrab", "$event schedule=$scheduleId code=$code $detail")
            HistoryRules.record(state, HistoryEntry(now, SystemClock.elapsedRealtime(), event, scheduleId, code, detail))
        }
    }

    private fun correctedNow(state: AppState): Long =
        if (state.correction.measuredAtMs > 0) state.correction.now(SystemClock.elapsedRealtime())
        else System.currentTimeMillis()

    override fun onDestroy() {
        app.store.update { it.copy(busy = false) }
        if (wakeLock.isHeld) wakeLock.release()
        WakeActivity.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        writer?.println(StatusReporter.line(app.store.load()))
    }

    private enum class RunDisposition { KEEP, REMOVE }

    companion object {
        const val ACTION_RUN = "com.tamacolet.coupongrab.RUN"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_KIND = "kind"
    }
}
