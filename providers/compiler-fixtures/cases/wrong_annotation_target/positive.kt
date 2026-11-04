package fixtures.wrong_annotation_target

@Target(AnnotationTarget.FUNCTION) annotation class OnlyFunction
@OnlyFunction class Wrong
