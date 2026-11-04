package fixtures.conflicting_upper_bounds

interface TextShape { val text: String }
interface SizeShape { val size: Int }
class Possible<T> where T : TextShape, T : SizeShape
