package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()
public class OtherFailure : Exception()

public interface Reader {
    @Throws(DomainFailure::class, OtherFailure::class)
    public fun read(): String
}

public class ReorderedReader : Reader {
    @Throws(OtherFailure::class, DomainFailure::class)
    override fun read(): String = "value"
}
