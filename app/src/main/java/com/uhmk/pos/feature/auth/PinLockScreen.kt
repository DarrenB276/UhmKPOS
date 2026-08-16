package com.uhmk.pos.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PinLockScreen(
    userName: String,
    autoUnlock: Boolean,
    onUnlock: (String) -> Boolean,
    onUseAnotherAccount: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun submit(candidate: String = pin, finalAttempt: Boolean = true) {
        if (candidate.length < 4) return
        if (!onUnlock(candidate) && finalAttempt) {
            error = true
            pin = ""
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Welcome, $userName", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Enter your launch PIN",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { index ->
                    Surface(
                        shape = CircleShape,
                        color = if (index < pin.length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(15.dp),
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { digit ->
                        FilledTonalIconButton(
                            onClick = {
                                error = false
                                if (pin.length < 6) {
                                    val candidate = pin + digit
                                    pin = candidate
                                    if (autoUnlock && candidate.length >= 4) {
                                        submit(candidate, finalAttempt = candidate.length == 6)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(62.dp),
                            shape = CircleShape,
                        ) {
                            Text(digit, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = {
                        error = false
                        if (pin.length < 6) {
                            val candidate = pin + "0"
                            pin = candidate
                            if (autoUnlock && candidate.length >= 4) {
                                submit(candidate, finalAttempt = candidate.length == 6)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(62.dp),
                    shape = CircleShape,
                ) { Text("0", style = MaterialTheme.typography.headlineSmall) }
                FilledTonalIconButton(
                    onClick = { pin = pin.dropLast(1); error = false },
                    enabled = pin.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(62.dp),
                    shape = CircleShape,
                ) { Icon(Icons.Default.Backspace, contentDescription = "Delete digit") }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = pin.length in 4..6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onUseAnotherAccount) {
                Text("Use a different account")
            }
        }
    }
}
