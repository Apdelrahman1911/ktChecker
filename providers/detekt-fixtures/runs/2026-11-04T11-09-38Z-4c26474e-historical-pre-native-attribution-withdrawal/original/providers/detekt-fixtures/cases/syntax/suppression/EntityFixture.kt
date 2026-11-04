package fixture

class Declaration(val nameIdentifier: String?)

object Entity {
    fun from(name: String?): String? = name
    fun atName(declaration: Declaration): String? = declaration.nameIdentifier
}

@Suppress("UseEntityAtName")
fun suppressedEntity(declaration: Declaration): String? = Entity.from(declaration.nameIdentifier)

fun adjacentEntity(declaration: Declaration): String? = Entity.from(declaration.nameIdentifier)
