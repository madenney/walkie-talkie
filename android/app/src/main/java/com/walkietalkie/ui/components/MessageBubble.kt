package com.walkietalkie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.walkietalkie.ui.viewmodel.ChatMessage
import com.walkietalkie.ui.viewmodel.Role

@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.role == Role.USER
    val isSystem = message.role == Role.SYSTEM
    val isTool = message.role == Role.TOOL

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
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    ))
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
