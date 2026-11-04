package fixtures.return_type_mismatch_on_override

interface Api { fun read(): Number }
class Reader : Api { override fun read(): Int = 1 }
