package fixtures.suspend_overridden_by_non_suspend

interface Api { suspend fun read(): Int }
class Reader : Api { override suspend fun read(): Int = 1 }
