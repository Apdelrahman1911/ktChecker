package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

@Throws(IllegalArgumentException::class)
public suspend fun loadMissingCancellation(): String = "value"
