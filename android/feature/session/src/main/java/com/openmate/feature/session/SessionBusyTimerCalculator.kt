package com.openmate.feature.session

import com.openmate.core.domain.model.SessionMessage

object SessionBusyTimerCalculator {
    fun displayDuration(totalDuration: Long?, currentBusyStart: Long?, now: Long): Long? {
        return if (currentBusyStart != null) {
            maxOf(0L, now - currentBusyStart)
        } else {
            totalDuration
        }
    }

    fun findBusyStart(messages: List<SessionMessage>): Long? {
        val busyTailIndex = messages.indexOfLast { it.type == "assistant" && it.completedAt == null }
            .takeIf { it >= 0 }
            ?: messages.lastIndex.takeIf { it >= 0 }
            ?: return null

        for (index in busyTailIndex downTo 0) {
            val message = messages[index]
            if (message.type != "user" || !message.roundMark) continue
            val hasCompletedAssistantAfter = messages.subList(index + 1, busyTailIndex + 1).any {
                it.type == "assistant" && it.completedAt != null && it.roundMark
            }
            if (!hasCompletedAssistantAfter) return message.timeCreated
            return null
        }

        return null
    }
}
