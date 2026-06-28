package com.walkietalkie.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.walkietalkie.ui.viewmodel.ChatMessage
import com.walkietalkie.ui.viewmodel.Role

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    // True when this bubble's text is the one currently being read aloud.
    isSpeaking: Boolean = false,
    // True when that spoken response is paused (vs actively reading).
    isPaused: Boolean = false,
    // Tap handler for the speaking bubble (pause/resume). Null = not tappable.
    onTapSpeaking: (() -> Unit)? = null,
) {
    val isUser = message.role == Role.USER
    val isSystem = message.role == Role.SYSTEM
    val isTool = message.role == Role.TOOL

    // Long-press any bubble to copy its contents to the clipboard (the usual
    // Android gesture). For a tool card we copy its output; otherwise the text.
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyContents = {
        val toCopy = if (isTool && !message.toolOutput.isNullOrEmpty()) message.toolOutput
                     else message.text
        if (!toCopy.isNullOrEmpty()) {
            clipboard.setText(AnnotatedString(toCopy))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    val alignment = when {
        isUser -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }

    val backgroundColor = when (message.role) {
        Role.USER -> MaterialTheme.colorScheme.primary
        Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        Role.TOOL -> MaterialTheme.colorScheme.tertiaryContainer
        Role.SYSTEM -> Color.Transparent
    }

    val textColor = when (message.role) {
        Role.USER -> MaterialTheme.colorScheme.onPrimary
        Role.ASSISTANT -> MaterialTheme.colorScheme.onSurfaceVariant
        Role.TOOL -> MaterialTheme.colorScheme.onTertiaryContainer
        Role.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = alignment
    ) {
        if (isSystem) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = textColor,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            val bubbleShape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            )

            // Animated highlight for the bubble being read aloud: a glowing accent
            // border that pulses while speaking and holds steady while paused.
            val accent = MaterialTheme.colorScheme.primary
            val pulse by rememberInfiniteTransition(label = "speak").animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
                label = "pulse",
            )
            val borderAlpha = when {
                !isSpeaking -> 0f
                isPaused -> 0.9f          // steady ring while paused
                else -> pulse             // breathing ring while reading
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(bubbleShape)
                    .border(2.dp, accent.copy(alpha = borderAlpha), bubbleShape)
                    .combinedClickable(
                        onClick = { if (isSpeaking && onTapSpeaking != null) onTapSpeaking() },
                        onLongClick = copyContents,
                    )
                    .background(backgroundColor)
                    .padding(12.dp)
            ) {
                if (isTool && message.toolName != null) {
                    Text(
                        text = message.toolName,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Check if text contains code blocks
                val hasCodeBlocks = message.text.contains("```")
                if (hasCodeBlocks) {
                    RichText(text = message.text, textColor = textColor)
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }

                if (isTool && !message.toolOutput.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    CodeBlock(
                        code = message.toolOutput,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (message.isStreaming) {
                    Text(
                        text = "\u2588", // blinking cursor effect
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.5f),
                    )
                }

                // Footer affordance on the bubble being read aloud, so it's clear
                // it's tappable and what a tap does.
                if (isSpeaking) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = accent.copy(alpha = if (isPaused) 0.9f else pulse),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) "Paused \u2014 tap to resume" else "Speaking \u2014 tap to pause",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders text with inline code blocks.
 */
@Composable
fun RichText(text: String, textColor: Color) {
    val parts = text.split("```")
    parts.forEachIndexed { index, part ->
        if (index % 2 == 0) {
            // Regular text
            if (part.isNotBlank()) {
                Text(
                    text = part.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        } else {
            // Code block — strip optional language tag on first line
            val code = part.trimStart().let { s ->
                val firstNewline = s.indexOf('\n')
                if (firstNewline > 0) s.substring(firstNewline + 1).trimEnd()
                else s.trimEnd()
            }
            Spacer(Modifier.height(8.dp))
            CodeBlock(code = code, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
    }
}
