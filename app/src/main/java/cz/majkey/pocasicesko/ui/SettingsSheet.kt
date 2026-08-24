package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.SupportedLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(selectedTag: String, onLanguage: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.settings),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.language),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SupportedLanguage.entries.forEach { language ->
                ListItem(
                    headlineContent = { Text(stringResource(language.labelResource())) },
                    trailingContent = {
                        if (language.tag == selectedTag) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguage(language.tag) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

private fun SupportedLanguage.labelResource(): Int = when (this) {
    SupportedLanguage.SYSTEM -> R.string.language_system
    SupportedLanguage.ENGLISH -> R.string.language_english
    SupportedLanguage.CZECH -> R.string.language_czech
    SupportedLanguage.GERMAN -> R.string.language_german
    SupportedLanguage.SPANISH -> R.string.language_spanish
    SupportedLanguage.FRENCH -> R.string.language_french
}
