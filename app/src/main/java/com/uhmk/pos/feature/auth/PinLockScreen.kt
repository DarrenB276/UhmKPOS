package com.uhmk.pos.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.ui.PosWindowSize
import com.uhmk.pos.core.ui.rememberWindowSize

/**
 * Sizing for the keypad, in one place so every part of it grows together.
 *
 * The keys used to be `weight(1f)` inside a full-width column. On a phone that looked right, but on
 * a tablet the row stretched to the whole screen and left short, wide slabs with a small glyph
 * floating in the middle. Fixed circular keys that scale with the window keep the proportions
 * honest, and the column is centred instead of stretched.
 */
private data class KeypadMetrics(
    val key: Dp,
    val gap: Dp,
    val dot: Dp,
    val lockIcon: Dp,
    val digitStyle: TextStyle,
    val titleStyle: TextStyle,
) {
    /** The keypad is three keys wide; everything else lines up with it. */
    val width: Dp get() = key * 3 + gap * 2
}

@Composable
private fun keypadMetrics(): KeypadMetrics {
    val type = MaterialTheme.typography
    return when (rememberWindowSize()) {
        PosWindowSize.COMPACT -> KeypadMetrics(
            key = 66.dp, gap = 14.dp, dot = 14.dp, lockIcon = 44.dp,
            digitStyle = type.headlineSmall, titleStyle = type.headlineSmall,
        )
        PosWindowSize.MEDIUM -> KeypadMetrics(
            key = 80.dp, gap = 18.dp, dot = 16.dp, lockIcon = 52.dp,
            digitStyle = type.headlineMedium, titleStyle = type.headlineMedium,
        )
        PosWindowSize.EXPANDED -> KeypadMetrics(
            key = 92.dp, gap = 22.dp, dot = 18.dp, lockIcon = 60.dp,
            digitStyle = type.headlineLarge, titleStyle = type.headlineMedium,
        )
    }
}

@Composable
fun PinLockScreen(
    userName: String,
    autoUnlock: Boolean,
    onUnlock: (String) -> Boolean,
    onUseAnotherAccount: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val m = keypadMetrics()

    fun submit(candidate: String = pin, finalAttempt: Boolean = true) {
        if (candidate.length < 4) return
        if (!onUnlock(candidate) && finalAttempt) {
            error = true
            pin = ""
        }
    }

    fun press(digit: String) {
        error = false
        if (pin.length >= 6) return
        val candidate = pin + digit
        pin = candidate
        if (autoUnlock && candidate.length >= 4) {
            submit(candidate, finalAttempt = candidate.length == 6)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(m.lockIcon),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text("Welcome, $userName", style = m.titleStyle)
                Text(
                    "Enter your launch PIN",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(m.gap * 0.7f)) {
                    repeat(6) { index ->
                        Surface(
                            shape = CircleShape,
                            color = if (index < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(m.dot),
                        ) {}
                    }
                }
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text("Incorrect PIN", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(22.dp))

                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(m.gap)) {
                        row.forEach { digit ->
                            FilledTonalIconButton(
                                onClick = { press(digit) },
                                modifier = Modifier.size(m.key),
                                shape = CircleShape,
                            ) {
                                Text(digit, style = m.digitStyle)
                            }
                        }
                    }
                    Spacer(Modifier.height(m.gap))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Keeps 0 under the 8 and the backspace under the 9.
                    Spacer(Modifier.size(m.key))
                    FilledTonalIconButton(
                        onClick = { press("0") },
                        modifier = Modifier.size(m.key),
                        shape = CircleShape,
                    ) { Text("0", style = m.digitStyle) }
                    FilledTonalIconButton(
                        onClick = { pin = pin.dropLast(1); error = false },
                        enabled = pin.isNotEmpty(),
                        modifier = Modifier.size(m.key),
                        shape = CircleShape,
                    ) {
                        Icon(
                            Icons.Default.Backspace,
                            contentDescription = "Delete digit",
                            modifier = Modifier.size(m.key * 0.36f),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { submit() },
                    enabled = pin.length in 4..6,
                    modifier = Modifier
                        .width(m.width)
                        .height(m.key * 0.78f),
                ) { Text("Unlock", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onUseAnotherAccount) {
                    Text("Use a different account")
                }
            }
        }
    }
}
