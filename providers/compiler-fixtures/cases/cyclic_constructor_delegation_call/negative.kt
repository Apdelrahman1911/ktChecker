package fixtures.cyclic_constructor_delegation_call

class Reader {
    constructor() : this(1)
    constructor(value: Int) { println(value) }
}
