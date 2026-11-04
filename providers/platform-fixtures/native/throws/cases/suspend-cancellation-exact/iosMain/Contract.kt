package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

@Throws(kotlin.coroutines.cancellation.CancellationException::class)
public suspend fun loadWithCancellation(): String = "value"

public typealias CancellationAlias = kotlin.coroutines.cancellation.CancellationException

@Throws(CancellationAlias::class)
public suspend fun loadWithCancellationAlias(): String = "value"
