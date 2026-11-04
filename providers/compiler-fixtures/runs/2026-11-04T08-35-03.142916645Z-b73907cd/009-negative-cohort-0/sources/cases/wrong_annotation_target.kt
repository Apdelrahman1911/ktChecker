package fixtures.wrong_annotation_target

@Target(AnnotationTarget.FUNCTION) annotation class OnlyFunction
@OnlyFunction fun correct() = 1
