package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public interface Loader {
    public suspend fun load(): String
}

public class ExplicitCancellationLoader : Loader {
    @Throws(kotlin.coroutines.cancellation.CancellationException::class)
    override suspend fun load(): String = "value"
}
