package com.openmate.core.network

import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile

object ActiveProfileProviderHolder : ActiveProfileProvider {
    var delegate: ActiveProfileProvider? = null

    override fun getActiveProfile() = delegate?.getActiveProfile()

    override fun getActiveRoute(): ConnectionRoute? = delegate?.getActiveRoute()
}
