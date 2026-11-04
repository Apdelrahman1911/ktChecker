package fixtures.opt_in_usage_error

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class ExperimentalContract
@ExperimentalContract fun experimental(): Int = 1
fun read(): Int = experimental()
