package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()
public class OtherFailure : Exception()

public interface FirstReader {
    @Throws(DomainFailure::class)
    public fun read(): String
}

public interface SecondReader {
    @Throws(OtherFailure::class)
    public fun read(): String
}

public class ConflictingReader : FirstReader, SecondReader {
    override fun read(): String = "value"
}
