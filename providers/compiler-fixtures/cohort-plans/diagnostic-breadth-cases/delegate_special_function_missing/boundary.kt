import kotlin.reflect.KProperty
class Slot
operator fun Slot.getValue(owner: Any?, property: KProperty<*>): Int = 7
val stored: Int by Slot()
