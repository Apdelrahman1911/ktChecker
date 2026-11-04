package fixtures.abstract_member_not_implemented

interface Api { fun read(): Int }
class Reader : Api { override fun read(): Int = 1 }
