package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()

public interface Reader {
    @Throws(Exception::class)
    public fun read(): String
}

public class NarrowedReader : Reader {
    @Throws(DomainFailure::class)
    override fun read(): String = "value"
}
