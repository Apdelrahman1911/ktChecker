package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()
public class OtherFailure : Exception()

public interface Reader {
    @Throws(DomainFailure::class)
    public fun read(): String
}

public class MatchingReader : Reader {
    @Throws(DomainFailure::class)
    override fun read(): String = "value"
}
