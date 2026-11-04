package fixtures.overriding_final_member

open class Base { open fun read(): Int = 1 }
class Reader : Base() { override fun read(): Int = 2 }
