package com.zcam.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

class DefaultDispatcherProvider @Inject constructor(
    @IoDispatcher override val io: CoroutineDispatcher,
    @DefaultDispatcher override val default: CoroutineDispatcher
) : DispatcherProvider
