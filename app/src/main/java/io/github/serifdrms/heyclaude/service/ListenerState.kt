package io.github.serifdrms.heyclaude.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ListenerStatus {
    data object Stopped : ListenerStatus
    data object Starting : ListenerStatus
    data object Listening : ListenerStatus
    data object PausedCooldown : ListenerStatus
    data object PausedMicBusy : ListenerStatus
    data object PausedShizuku : ListenerStatus
    data class Error(val reason: ErrorReason, val detail: String? = null) : ListenerStatus
}

enum class ErrorReason { MODELS, MICROPHONE, PERMISSION, FOREGROUND }

/** Shared between the service and the UI; both live in the same process. */
object ListenerState {
    private val _status = MutableStateFlow<ListenerStatus>(ListenerStatus.Stopped)
    val status = _status.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level = _level.asStateFlow()

    internal fun setStatus(value: ListenerStatus) {
        _status.value = value
        if (value != ListenerStatus.Listening) _level.value = 0f
    }

    internal fun setLevel(value: Float) {
        _level.value = value
    }
}
