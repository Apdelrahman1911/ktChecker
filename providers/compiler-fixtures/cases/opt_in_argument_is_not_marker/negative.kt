package fixtures.opt_in_argument_is_not_marker

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn annotation class Plain
@OptIn(Plain::class) fun read(): Int = 1
