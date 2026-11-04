package fixtures.delegation_by_in_jvm_record

interface Api { fun read(): Int }
@JvmRecord data class Reader(val source: Api) : Api { override fun read(): Int = source.read() }
