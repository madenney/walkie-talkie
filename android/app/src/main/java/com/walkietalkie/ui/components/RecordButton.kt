package com.walkietalkie.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.walkietalkie.audio.VadState

/**
 * Mute/unmute toggle button with visual feedback for VAD state.
 */
@Composable
fun RecordButton(
    isMuted: Boolean,
    isEnabled: Boolean,
    listeningState: VadState,
    isPlayingAudio: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSpeech = listeningState == VadState.SPEECH || listeningState == VadState.COOLDOWN
    val isListening = listeningState == VadState.LISTENING

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant
            isMuted -> MaterialTheme.colorScheme.surfaceVariant
            isSpeech -> MaterialTheme.colorScheme.error
            isListening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "recordButtonColor"
    )

    // Pulse when speech detected, subtle breathe when listening
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when {
            isSpeech -> 1.15f
            isListening && !isMuted -> 1.05f
            else -> 1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeech) 600 else 2000,
                easing = EaseInOut,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .size(64.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                detectTapGestures(
                    onTap = { onToggleMute() }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isMuted || !isEnabled) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (isMuted) "Unmute" else "Mute",
            tint = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                isMuted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> Color.White
            },
            modifier = Modifier.size(32.dp),
        )
    }
}
