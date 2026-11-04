package fixtures.var_overridden_by_val

interface Api { var value: Int }
class Reader : Api { override val value: Int = 1 }
