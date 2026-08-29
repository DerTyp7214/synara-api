package dev.dertyp.plugins

import dev.dertyp.data.UserInfo
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiIcon

data class IntakeReceipt(
    val accepted: Int,
    val messageKey: String? = null,
    val next: UiAction? = null,
)

class IntakeOffer(
    val accepted: List<IntakeItem>,
    val titleKey: String? = null,
    val descriptionKey: String? = null,
    val icon: UiIcon? = null,
    val confirmKey: String? = null,
    val priority: Int = 0,
    val titleArgs: Map<String, String> = emptyMap(),
    val action: UiAction? = null,
    val submit: (suspend () -> IntakeReceipt)? = null,
) {
    init {
        require((action == null) != (submit == null)) { "An intake offer has either an action or a submit function" }
    }
}

interface IntakeResolver {
    val id: String
    val titleKey: String
    val descriptionKey: String? get() = null
    val icon: UiIcon? get() = null
    val jobKind: String
    val access: UiAccess get() = UiAccess()

    suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer?
}

fun interface IntakeRegistrar {
    fun register(resolver: IntakeResolver): UiRegistration
}
