package fixtures.non_suspend_overridden_by_suspend

interface Api { fun read(): Int }
class Reader : Api { override fun read(): Int = 1 }
