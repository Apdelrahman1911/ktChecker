@file:Suppress("FIELD_IN_JVM_RECORD")
package fixtures.field_in_jvm_record

@JvmRecord data class Record(val value: Int) { val extra = 1 }
