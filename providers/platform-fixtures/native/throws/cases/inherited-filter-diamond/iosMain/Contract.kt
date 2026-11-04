package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()

public interface RootReader {
    @Throws(DomainFailure::class)
    public fun read(): String
}
public interface LeftReader : RootReader
public interface RightReader : RootReader

public class SharedRootReader : LeftReader, RightReader {
    override fun read(): String = "value"
}
