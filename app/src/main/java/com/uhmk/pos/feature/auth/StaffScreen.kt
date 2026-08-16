package com.uhmk.pos.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.model.UserRole
import com.uhmk.pos.core.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffScreen(
    staff: List<UserEntity>,
    cloudEnabled: Boolean,
    onCreate: (String, String, String, UserRole) -> Unit,
    onSetActive: (String, Boolean) -> Unit,
    onSetRole: (String, UserRole) -> Unit,
    onBack: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Staff & admins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (cloudEnabled) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Add account") },
                )
            }
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner)) {
            if (!cloudEnabled) {
                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Firebase needed", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Staff sign in with their own email and password, which is handled " +
                                    "by Firebase Authentication. Follow FIREBASE_SETUP.md, rebuild, " +
                                    "and this screen becomes active.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (staff.isEmpty()) {
                item {
                    Spacer(Modifier.height(40.dp))
                    EmptyState(title = "No accounts yet", body = "Staff you add will show up here.")
                }
            }

            items(staff, key = { it.uid }) { user ->
                StaffRow(
                    user = user,
                    onSetActive = { onSetActive(user.uid, it) },
                    onSetRole = { onSetRole(user.uid, it) },
                )
            }
        }
    }

    if (adding) {
        AddStaffDialog(
            onDismiss = { adding = false },
            onCreate = { email, password, name, role ->
                onCreate(email, password, name, role)
                adding = false
            },
        )
    }
}

@Composable
private fun StaffRow(
    user: UserEntity,
    onSetActive: (Boolean) -> Unit,
    onSetRole: (UserRole) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(user.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        user.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = user.active, onCheckedChange = onSetActive)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserRole.entries.forEach { role ->
                    AssistChip(
                        onClick = { onSetRole(role) },
                        label = { Text(role.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        leadingIcon = if (user.role == role) {
                            { Text("✓") }
                        } else null,
                    )
                }
                if (!user.active) {
                    Text(
                        "disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddStaffDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, UserRole) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.STAFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New staff or admin") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Temporary password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("At least 6 characters. They can change it later.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UserRole.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = role == option,
                            onClick = { role = option },
                            shape = SegmentedButtonDefaults.itemShape(index, UserRole.entries.size),
                        ) { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(email, password, name, role) },
                enabled = email.isNotBlank() && password.length >= 6,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
