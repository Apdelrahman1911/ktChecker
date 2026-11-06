@file:Suppress("DELEGATE_SPECIAL_FUNCTION_RETURN_TYPE_MISMATCH")
import kotlin.reflect.KProperty
class Slot {
    operator fun getValue(owner: Any?, property: KProperty<*>): String = "seven"
}
val stored: Int by Slot()
