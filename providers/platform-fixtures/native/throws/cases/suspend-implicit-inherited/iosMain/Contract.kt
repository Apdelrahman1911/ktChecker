package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public interface Loader {
    public suspend fun load(): String
}

public class ImplicitCancellationLoader : Loader {
    override suspend fun load(): String = "value"
}
