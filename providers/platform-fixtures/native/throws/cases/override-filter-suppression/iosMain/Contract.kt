package dev.ktchecker.fixture.nativecontracts

import kotlin.Throws

public class DomainFailure : Exception()
public class OtherFailure : Exception()

public interface Reader {
    @Throws(DomainFailure::class)
    public fun read(): String
}

public class BrokenReader : Reader {
    @Suppress("INCOMPATIBLE_THROWS_OVERRIDE")
    @Throws(OtherFailure::class)
    override fun read(): String = "value"
}

public class AdjacentBrokenReader : Reader {
    @Throws(OtherFailure::class)
    override fun read(): String = "adjacent"
}
