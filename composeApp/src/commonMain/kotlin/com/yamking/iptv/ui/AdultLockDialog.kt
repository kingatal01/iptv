package com.yamking.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yamking.iptv.AppState

private const val MIN_DIGITS = 4

/**
 * Code de la recherche de contenu pour adultes : création, déverrouillage, reverrouillage.
 * Il n'est demandé qu'au moment d'une recherche ; la bibliothèque et les imports ne sont pas concernés.
 */
@Composable
fun AdultLockDialog(state: AppState, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Palette.Surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recherche pour adultes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when {
                state.adultUnlocked -> {
                    Text(
                        "Déverrouillée. La recherche de chaînes pour adultes est autorisée jusqu'à la fermeture de l'application.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextMuted,
                    )
                    Buttons(onDismiss, "Verrouiller maintenant", enabled = true) {
                        state.lockAdult()
                        onDismiss()
                    }
                }
                !state.hasAdultPin -> {
                    Text(
                        "La recherche de chaînes pour adultes est verrouillée par défaut. Choisissez un code d'au moins $MIN_DIGITS chiffres pour l'autoriser. Il sera redemandé à la première recherche de ce type après chaque ouverture de l'application.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextMuted,
                    )
                    PinField("Nouveau code", pin) { pin = it }
                    PinField("Confirmer le code", confirmation, isError = confirmation.isNotEmpty() && confirmation != pin) { confirmation = it }
                    Text(
                        "Réservé aux adultes. Si vous oubliez ce code, le seul recours est « Vider la bibliothèque ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Danger,
                    )
                    Buttons(onDismiss, "Définir le code", enabled = pin.length >= MIN_DIGITS && pin == confirmation) {
                        if (state.setAdultPin(pin)) onDismiss()
                    }
                }
                else -> {
                    Text(
                        "Saisissez votre code pour autoriser la recherche de chaînes pour adultes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextMuted,
                    )
                    PinField("Code", pin, isError = wrong) {
                        pin = it
                        wrong = false
                    }
                    if (wrong) Text("Code incorrect.", color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
                    Buttons(onDismiss, "Déverrouiller", enabled = pin.length >= MIN_DIGITS) {
                        if (state.unlockAdult(pin)) onDismiss() else wrong = true
                    }
                }
            }
        }
    }
}

@Composable
private fun PinField(label: String, value: String, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        // Le code est masqué à l'écran et saisi au clavier numérique.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Buttons(onDismiss: () -> Unit, action: String, enabled: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("Fermer", color = Palette.TextMuted) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAction, enabled = enabled, shape = MaterialTheme.shapes.medium) { Text(action) }
    }
}
