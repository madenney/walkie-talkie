package com.walkietalkie.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Big hold-to-talk button. Press and hold to open the mic and stream audio;
 * release to send. Gives a haptic buzz on press and a tick on release so it's
 * usable eyes-free (walking, driving).
 */
@Composable
fun PushToTalkButton(
    isRecording: Boolean,
    isEnabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    val background by animateColorAsState(
        targetValue = when {
            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant
            isRecording -> MaterialTheme.colorScheme.error      // red while transmitting
            else -> MaterialTheme.colorScheme.primary
        },
        label = "pttColor",
    )

    // Gentle pulse while held so the screen confirms it's live.
    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pttPulseScale",
    )

    val contentColor =
        if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .scale(if (isRecording) pulse else 1f)
            .clip(CircleShape)
            .background(background)
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.any { it.pressed }
                        
                        if (down && event.changes.any { !it.previousPressed && it.pressed }) {
                            // Finger just went down
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPressStart()
                        } else if (!down && event.changes.any { it.previousPressed && !it.pressed }) {
                            // Finger just lifted
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPressEnd()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    !isEnabled -> "Disconnected"
                    isRecording -> "Release to send"
                    else -> "Hold to talk"
                },
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
        }
    }
}
