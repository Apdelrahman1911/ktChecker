package fixtures.property_type_mismatch_on_override

interface Api { val value: Int }
class Reader : Api { override val value: Int = 1 }
