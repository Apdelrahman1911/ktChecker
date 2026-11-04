package fixtures.opt_in_argument_is_not_marker

annotation class Plain
@OptIn(Plain::class) fun read(): Int = 1
