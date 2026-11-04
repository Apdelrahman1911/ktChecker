package fixtures.invisible_setter

class Vault { var value: Int = 0 }
fun write(vault: Vault) { vault.value = 1 }
