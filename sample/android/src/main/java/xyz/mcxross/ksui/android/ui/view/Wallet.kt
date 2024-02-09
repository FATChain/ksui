package xyz.mcxross.ksui.android.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun Wallet(modifier: Modifier = Modifier, onNewWallet: () -> Unit) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Button(onClick = onNewWallet) { Text("Create Wallet") }
  }
}
