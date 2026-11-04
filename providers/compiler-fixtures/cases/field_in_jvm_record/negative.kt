package fixtures.field_in_jvm_record

@JvmRecord data class Record(val value: Int) { val extra get() = 1 }
