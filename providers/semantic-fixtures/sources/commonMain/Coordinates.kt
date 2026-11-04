package fixture.semantic

// Raw CRLF and supplementary Unicode are intentional: 🙂
fun coordinateUse(): String {
    val π = "é🙂"
    return choose("é🙂") + π
}
