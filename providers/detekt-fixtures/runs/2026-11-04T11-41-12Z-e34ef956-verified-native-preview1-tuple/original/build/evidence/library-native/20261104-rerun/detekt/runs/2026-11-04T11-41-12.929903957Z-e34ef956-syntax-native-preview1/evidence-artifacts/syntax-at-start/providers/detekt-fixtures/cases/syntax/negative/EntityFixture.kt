package fixture

class Declaration(val nameIdentifier: String?)

object Entity {
    fun from(name: String?): String? = name
    fun atName(declaration: Declaration): String? = declaration.nameIdentifier
}

fun reportedEntity(declaration: Declaration): String? = Entity.atName(declaration)
