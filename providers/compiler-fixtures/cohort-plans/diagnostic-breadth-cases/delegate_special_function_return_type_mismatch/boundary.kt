import kotlin.reflect.KProperty
class Slot {
    operator fun getValue(owner: Any?, property: KProperty<*>): String = "seven"
}
val stored: CharSequence by Slot()
