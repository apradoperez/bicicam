package es.bicilab.bicicam.extension

/** Bonus actions declared in `extension_info.xml`, assignable to Karoo/AXS buttons. */
enum class BiciCamAction(val actionId: String) {
    TOGGLE_RECORDING("toggle-recording"),
    ADD_MARKER("add-marker"),
    ;

    companion object {
        fun fromActionId(actionId: String): BiciCamAction? = entries.firstOrNull { it.actionId == actionId }
    }
}
