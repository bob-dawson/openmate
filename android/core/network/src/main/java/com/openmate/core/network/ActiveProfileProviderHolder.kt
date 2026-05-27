package com.openmate.core.network

object ActiveProfileProviderHolder : ActiveProfileProvider {
    var delegate: ActiveProfileProvider? = null

    override fun getActiveProfile() = delegate?.getActiveProfile()
}
