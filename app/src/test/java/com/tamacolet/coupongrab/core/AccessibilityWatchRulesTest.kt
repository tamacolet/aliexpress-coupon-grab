package com.tamacolet.coupongrab.core

import org.junit.Assert.*
import org.junit.Test

class AccessibilityWatchRulesTest {
    private val schedule = Schedule(id = "test", kind = ScheduleKind.EXACT,
        exactAtMs = 9_000_000, nextRunAtMs = 9_000_000, codes = listOf(CouponCode("TEST123")))

    @Test fun checkIsTenMinutesBeforeTargetWithoutExecutionPreparationLead() {
        assertEquals(8_400_000L, AccessibilityWatchRules.checkTriggerAt(schedule.nextRunAtMs))
    }

    @Test fun correctedClockUsesSameOffsetDirectionAsScheduleRules() {
        for (offset in listOf(-7_000L, 0L, 9_000L)) {
            val checkAt = AccessibilityWatchRules.checkTriggerAt(schedule.nextRunAtMs, offset)
            assertEquals(schedule.nextRunAtMs - 600_000L, checkAt + offset)
            assertEquals(ScheduleRules.alarmTriggerAt(ScheduleKind.REPEAT, schedule.nextRunAtMs, offset),
                checkAt + 600_000L)
        }
    }

    @Test fun lateCreationIsAlreadyDueAtAndAfterCheckTime() {
        val at = AccessibilityWatchRules.checkTriggerAt(schedule.nextRunAtMs)
        assertFalse(AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, 0, at - 1))
        assertTrue(AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, 0, at))
        assertTrue(AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, 0, at + 1))
        assertTrue(AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, 5_000, at - 5_000))
        assertFalse(AccessibilityWatchRules.checkDue(schedule.nextRunAtMs, -5_000, at))
    }

    @Test fun exactDisconnectedIsWarnedOnceIncludingAfterStateRestoration() {
        val first = AccessibilityWatchRules.exact(checked = false, connected = false)
        assertTrue(first.notify)
        assertFalse(AccessibilityWatchRules.exact(checked = first.checked, connected = false).notify)
    }

    @Test fun exactConnectedCheckStillCountsAsChecked() {
        val first = AccessibilityWatchRules.exact(checked = false, connected = true)
        assertFalse(first.notify)
        assertFalse(AccessibilityWatchRules.exact(checked = first.checked, connected = false).notify)
    }

    @Test fun repeatWarnsInitiallyAndAfterRecoveryButNotEachExecution() {
        val first = AccessibilityWatchRules.repeat(null, false)
        assertTrue(first.notify)
        val nextExecution = AccessibilityWatchRules.repeat(first.connected, false)
        assertFalse(nextExecution.notify)
        val recovery = AccessibilityWatchRules.repeat(nextExecution.connected, true)
        assertFalse(recovery.notify)
        assertTrue(AccessibilityWatchRules.repeat(recovery.connected, false).notify)
        assertFalse(AccessibilityWatchRules.repeat(null, true).notify)
    }

    @Test fun serviceReconnectRearmsWarningWhileActivityIsClosed() {
        val first = AccessibilityWatchRules.repeat(null, false)
        assertTrue(first.notify)
        // onServiceConnected records true without any Activity or execution check.
        assertTrue(AccessibilityWatchRules.shouldRearmRepeat(first.connected))
        val persistedConnected = true
        val afterDisconnect = AccessibilityWatchRules.repeat(persistedConnected, false)
        assertTrue(afterDisconnect.notify)
        assertFalse(AccessibilityWatchRules.repeat(afterDisconnect.connected, false).notify)
    }

    @Test fun repeatedConnectedObservationDoesNotRequestAnotherWrite() {
        assertTrue(AccessibilityWatchRules.shouldRearmRepeat(null))
        assertTrue(AccessibilityWatchRules.shouldRearmRepeat(false))
        assertFalse(AccessibilityWatchRules.shouldRearmRepeat(true))
        assertFalse(AccessibilityWatchRules.repeat(true, true).notify)
    }

    @Test fun staleCheckCannotActAfterEditDisableDeleteOrNextOccurrence() {
        val expected = AccessibilityWatchRules.occurrence(schedule)
        assertTrue(AccessibilityWatchRules.matches(schedule, expected))
        assertFalse(AccessibilityWatchRules.matches(schedule.copy(nextRunAtMs = schedule.nextRunAtMs + 1), expected))
        assertFalse(AccessibilityWatchRules.matches(schedule.copy(enabled = false), expected))
        assertFalse(AccessibilityWatchRules.matches(null, expected))
        assertFalse(AccessibilityWatchRules.matches(schedule.copy(codes = listOf(CouponCode("EDIT123"))), expected))
        assertFalse(AccessibilityWatchRules.matches(schedule.copy(kind = ScheduleKind.REPEAT), expected))
        assertFalse(AccessibilityWatchRules.matches(schedule.copy(repeatMinutes = 10), expected))
        assertFalse(AccessibilityWatchRules.matches(schedule, null))
    }

    @Test fun editingCodesInSameExactOccurrenceDoesNotRepeatWarning() {
        val edited = schedule.copy(codes = listOf(CouponCode("EDIT123")))
        assertEquals(AccessibilityWatchRules.exactOccurrence(schedule), AccessibilityWatchRules.exactOccurrence(edited))
        assertNotEquals(AccessibilityWatchRules.exactOccurrence(schedule),
            AccessibilityWatchRules.exactOccurrence(schedule.copy(nextRunAtMs = schedule.nextRunAtMs + 1)))
    }

    @Test fun codeOutcomeDoesNotCreateNewOccurrenceOrDuplicateWarning() {
        val completedCode = schedule.copy(codes = listOf(CouponCode("TEST123", CodeStatus.APPLIED)))
        assertEquals(AccessibilityWatchRules.occurrence(schedule), AccessibilityWatchRules.occurrence(completedCode))
    }
}
