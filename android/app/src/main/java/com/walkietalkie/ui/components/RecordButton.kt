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
import androidx.compose.material.icons.filled.Stop
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

/**
 * Push-to-talk style record button.
 * Tap to start recording, tap again to stop.
 */
@Composable
fun RecordButton(
    isRecording: Boolean,
    isEnabled: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isRecording -> MaterialTheme.colorScheme.error
            isEnabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "recordButtonColor"
    )

    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
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
            .pointerInput(isEnabled, isRecording) {
                if (!isEnabled) return@pointerInput
                detectTapGestures(
                    onTap = {
                        if (isRecording) onStopRecording() else onStartRecording()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when {
                isRecording -> Icons.Default.Stop
                isEnabled -> Icons.Default.Mic
                else -> Icons.Default.MicOff
            },
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
            tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
    }
}
