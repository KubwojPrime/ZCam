package com.zcam.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HistoryPagingTest {
    @Test
    fun fetches_all_pages_without_an_initial_time_filter() = runTest {
        val calls = mutableListOf<Long?>()
        val result = fetchHistoryPages(2, { it: Long -> it }) { before, limit ->
            calls += before
            ClientCallResult.Success(listOf(5L, 4L, 3L, 2L, 1L).filter { before == null || it <= before }.take(limit))
        }
        assertEquals(listOf(null, 3L, 1L), calls)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), (result as ClientCallResult.Success).value)
    }

    @Test
    fun refuses_a_server_that_ignores_the_cursor_instead_of_looping_forever() = runTest {
        val result = fetchHistoryPages(2, { it: Long -> it }) { _, _ -> ClientCallResult.Success(listOf(3L, 2L)) }
        assertEquals("history_pagination_stalled", (result as ClientCallResult.Failure).reason)
    }

    @Test
    fun page_failure_does_not_return_a_partial_history_as_complete() = runTest {
        val result = fetchHistoryPages(2, { it: Long -> it }) { before, _ ->
            if (before == null) ClientCallResult.Success(listOf(3L, 2L)) else ClientCallResult.Failure(503, "offline")
        }
        assertEquals("offline", (result as ClientCallResult.Failure).reason)
    }
}
