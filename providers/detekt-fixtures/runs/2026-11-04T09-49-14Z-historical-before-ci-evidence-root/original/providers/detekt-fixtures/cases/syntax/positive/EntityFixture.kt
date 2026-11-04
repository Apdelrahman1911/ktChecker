package fixture

// The upstream rule is syntax-only. These declarations intentionally avoid
// claiming integration with the resolved Detekt rule-author API.
class Declaration(val nameIdentifier: String?)

object Entity {
    fun from(name: String?): String? = name
    fun atName(declaration: Declaration): String? = declaration.nameIdentifier
}

fun reportedEntity(declaration: Declaration): String? = Entity.from(declaration.nameIdentifier)
