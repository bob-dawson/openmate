package com.openmate.feature.session

data class PagingAnchor(
    val id: String,
    val offset: Int,
    val messageCount: Int,
)

object SessionPagingTrigger {
    data class State(
        val lastTriggeredFirstMessageId: String?,
    )

    fun shouldLoadOlder(
        state: State,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        prefetchDistancePx: Int,
        firstMessageId: String?,
        hasOlderMessages: Boolean,
        isLoadingOlder: Boolean,
        shouldFollow: Boolean,
    ): Boolean {
        val distanceToTop = when (firstVisibleItemIndex) {
            0 -> firstVisibleItemScrollOffset
            1 -> prefetchDistancePx
            else -> Int.MAX_VALUE
        }
        return hasOlderMessages &&
            !isLoadingOlder &&
            !shouldFollow &&
            distanceToTop <= prefetchDistancePx &&
            firstMessageId != null &&
            state.lastTriggeredFirstMessageId != firstMessageId
    }

    fun onTriggered(state: State, firstMessageId: String?): State {
        return state.copy(lastTriggeredFirstMessageId = firstMessageId)
    }
}
