package dev.phomo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.phomo.sip.EncryptedSipAccountStore
import dev.phomo.ui.AccountSetupScreen
import dev.phomo.ui.PhomoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhomoTheme {
                val viewModel: AccountSetupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AccountSetupViewModel(EncryptedSipAccountStore(applicationContext)) }
                    },
                )
                AccountSetupScreen(viewModel)
            }
        }
    }
}
