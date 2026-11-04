package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

@Throws(IllegalStateException::class)
public suspend fun loadWithCancellationSuperclass(): String = "value"

@Throws(RuntimeException::class)
public suspend fun loadWithRuntimeException(): String = "value"

@Throws(Exception::class)
public suspend fun loadWithException(): String = "value"

@Throws(Throwable::class)
public suspend fun loadWithThrowable(): String = "value"
