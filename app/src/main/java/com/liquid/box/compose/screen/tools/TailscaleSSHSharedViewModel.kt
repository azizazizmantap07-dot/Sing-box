package com.liquid.box.compose.screen.tools

import com.liquid.box.compose.base.BaseViewModel
import com.liquid.box.terminal.TailscaleSSHPresentedSession

data class TailscaleSSHSharedState(
    val pendingSession: TailscaleSSHPresentedSession? = null,
)

class TailscaleSSHSharedViewModel : BaseViewModel<TailscaleSSHSharedState, Nothing>() {
    override fun createInitialState() = TailscaleSSHSharedState()

    fun setPendingSession(session: TailscaleSSHPresentedSession) {
        updateState { copy(pendingSession = session) }
    }

    fun consumePendingSession(): TailscaleSSHPresentedSession? {
        val session = currentState.pendingSession
        updateState { copy(pendingSession = null) }
        return session
    }
}
