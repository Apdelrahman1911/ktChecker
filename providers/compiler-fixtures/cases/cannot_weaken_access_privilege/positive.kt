package fixtures.cannot_weaken_access_privilege

open class Base { open fun read(): Int = 1 }
class Reader : Base() { protected override fun read(): Int = 2 }
