package com.zcam.client

/** Fetch newest-to-oldest pages until the server's complete retained history is loaded. */
suspend fun <T> fetchHistoryPages(
    pageSize: Int,
    timestamp: (T) -> Long,
    fetch: suspend (beforeEpochMs: Long?, limit: Int) -> ClientCallResult<List<T>>
): ClientCallResult<List<T>> {
    val items = mutableListOf<T>()
    var before: Long? = null
    while (true) {
        when (val result = fetch(before, pageSize)) {
            is ClientCallResult.Failure -> return result
            is ClientCallResult.Success -> {
                val page = result.value
                items += page
                if (page.size < pageSize) return ClientCallResult.Success(items)
                val oldest = page.minOf(timestamp)
                if (oldest <= 0L) return ClientCallResult.Success(items)
                val next = oldest - 1
                if (before != null && next >= before) return ClientCallResult.Failure(null, "history_pagination_stalled")
                before = next
            }
        }
    }
}
