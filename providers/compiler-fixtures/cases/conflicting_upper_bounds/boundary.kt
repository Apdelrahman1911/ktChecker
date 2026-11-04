package fixtures.conflicting_upper_bounds

open class Base
interface Tag
class Bounded<T> where T : Base, T : Tag
