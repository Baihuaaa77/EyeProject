package com.zkyjss.eyeproject.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeClockTest {
    @Test fun refreshesAtMidnightWithoutAnyDataChangeAndStopsWithTheSubscriber() = runTest {
        val times = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            homeClockTicks { 86_399_000L + testScheduler.currentTime }.collect { times += it }
        }
        assertEquals(listOf(86_399_000L), times)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(86_399_000L, 86_400_000L), times)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(86_460_000L, times.last())
        job.cancel(); advanceTimeBy(120_000)
        assertEquals(3, times.size)
    }
}
