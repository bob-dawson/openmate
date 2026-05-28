package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile

interface ActiveProfileProvider {
    fun getActiveProfile(): ServerProfile?
    fun getActiveRoute(): ConnectionRoute?
}
