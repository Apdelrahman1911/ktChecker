package fixtures.opt_in_without_arguments

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn annotation class ExperimentalContract
@OptIn(ExperimentalContract::class) fun read(): Int = 1
