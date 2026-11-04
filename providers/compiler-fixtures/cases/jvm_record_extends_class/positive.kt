package fixtures.jvm_record_extends_class

open class Base
@JvmRecord data class Reader(val value: Int) : Base()
