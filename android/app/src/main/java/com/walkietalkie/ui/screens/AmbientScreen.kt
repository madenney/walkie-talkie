package com.walkietalkie.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.walkietalkie.audio.VadState
import com.walkietalkie.ui.viewmodel.ChatUiState
import com.walkietalkie.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Blue = Color(0xFF4488FF)
private val Red = Color(0xFFFF4444)
private val Yellow = Color(0xFFFFBB33)
private val Green = Color(0xFF44CC66)
private val Gray = Color(0xFF888888)

@Composable
fun AmbientScreen(
    viewModel: ChatViewModel,
    onExit: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // Force minimum brightness and keep screen on
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val originalBrightness = window.attributes.screenBrightness
        val originalFlags = window.attributes.flags

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.01f
        }

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
            }
        }
    }

    // Ambient is the hands-free VAD mode. The main screen no longer auto-listens,
    // so start voice detection on entry and stop it when we leave.
    DisposableEffect(Unit) {
        viewModel.onEnterAmbient()
        onDispose { viewModel.onExitAmbient() }
    }

    // Gesture flash state
    var flashWhite by remember { mutableStateOf(false) }

    fun flash() {
        scope.launch {
            flashWhite = true
            delay(150)
            flashWhite = false
        }
    }

    // Determine dot color from state
    val dotColor = dotColorForState(uiState, flashWhite)

    // Pulsing animation
    val isSpeech = uiState.listeningState == VadState.SPEECH ||
            uiState.listeningState == VadState.COOLDOWN

    val infiniteTransition = rememberInfiniteTransition(label = "ambientPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeech) 1.5f else 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeech) 400 else 1500,
                easing = EaseInOut,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeech) 0.6f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeech) 400 else 1500,
                easing = EaseInOut,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val animatedColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(300),
        label = "dotColor",
    )

    val baseDotSize = 24.dp
    val dotSize = baseDotSize * pulseScale

    // Swipe tracking
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                    },
                    onDragEnd = {
                        if (dragAccumulator < -150f) {
                            // Swipe up — exit
                            flash()
                            onExit()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        flash()
                        viewModel.toggleMute()
                    },
                    onDoubleTap = {
                        flash()
                        viewModel.interrupt()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = pulseAlpha))
        )
    }
}

private fun dotColorForState(state: ChatUiState, flashWhite: Boolean): Color {
    if (flashWhite) return Color.White

    val isSpeech = state.listeningState == VadState.SPEECH ||
            state.listeningState == VadState.COOLDOWN
    val activePage = state.pages.getOrNull(state.activePageIndex)

    return when {
        state.isMuted -> Gray
        state.isPlayingAudio -> Green
        activePage?.isResponding == true -> Yellow
        isSpeech -> Red
        state.listeningState == VadState.LISTENING -> Blue
        !state.isConnected -> Gray
        else -> Blue
    }
}
