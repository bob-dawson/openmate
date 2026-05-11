package com.openmate.feature.session

object SessionPagingTrigger {
    data class State(
        val lastTriggeredFirstMessageId: String?,
    )

    fun shouldLoadOlder(
        state: State,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        firstMessageId: String?,
        hasOlderMessages: Boolean,
        isLoadingOlder: Boolean,
        shouldFollow: Boolean,
    ): Boolean {
        return hasOlderMessages &&
            !isLoadingOlder &&
            !shouldFollow &&
            firstVisibleItemIndex == 0 &&
            firstVisibleItemScrollOffset == 0 &&
            firstMessageId != null &&
            state.lastTriggeredFirstMessageId != firstMessageId
    }

    fun onTriggered(state: State, firstMessageId: String?): State {
        return state.copy(lastTriggeredFirstMessageId = firstMessageId)
    }
}
