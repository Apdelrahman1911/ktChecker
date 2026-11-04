package fixtures.invisible_reference

class Vault { private fun secret(): Int = 1 }
fun read(): Int = Vault().secret()
