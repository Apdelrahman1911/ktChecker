package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

@Throws(IllegalStateException::class)
public fun readNonemptyContract(): String = "value"
