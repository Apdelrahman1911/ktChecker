package fixtures.inapplicable_jvm_name

open class Base { open fun read(): Int = 1 }
class Reader : Base() { @JvmName("renamed") override fun read(): Int = 2 }
