package fixtures.opt_in_usage

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalContract
@ExperimentalContract fun experimental(): Int = 1
fun read(): Int = experimental()
