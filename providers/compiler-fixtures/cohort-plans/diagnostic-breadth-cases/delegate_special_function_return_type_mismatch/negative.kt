import kotlin.reflect.KProperty
class Slot {
    operator fun getValue(owner: Any?, property: KProperty<*>): Int = 7
}
val stored: Int by Slot()
