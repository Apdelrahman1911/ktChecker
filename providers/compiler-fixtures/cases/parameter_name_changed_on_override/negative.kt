package fixtures.parameter_name_changed_on_override

interface Api { fun read(input: Int): Int }
class Reader : Api { override fun read(input: Int): Int = input }
