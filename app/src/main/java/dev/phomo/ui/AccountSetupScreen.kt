package dev.phomo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phomo.AccountSetupUiState
import dev.phomo.AccountSetupViewModel
import dev.phomo.R
import dev.phomo.sip.SipAccountError
import dev.phomo.sip.SipAccountField
import dev.phomo.sip.SipTransport

/** ViewModel-backed account-setup screen. */
@Composable
internal fun AccountSetupScreen(viewModel: AccountSetupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountSetupContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onDomainChange = viewModel::onDomainChange,
        onPasswordChange = viewModel::onPasswordChange,
        onOutboundProxyChange = viewModel::onOutboundProxyChange,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onTransportChange = viewModel::onTransportChange,
        onSave = viewModel::save,
    )
}

/**
 * Stateless account-setup form. All state and callbacks are hoisted so this can
 * be rendered directly in a screenshot test without a ViewModel or store.
 */
@Composable
internal fun AccountSetupContent(
    state: AccountSetupUiState,
    onUsernameChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onOutboundProxyChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onTransportChange: (SipTransport) -> Unit,
    onSave: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // MainActivity draws edge-to-edge, so inset the content out from
                // under the status/navigation bars and any display cutout before
                // applying the form's own padding. The Surface background stays
                // edge-to-edge behind the system bars.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.account_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.account_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            AccountField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.account_setup_username_label),
                error = errorMessage(state.errors[SipAccountField.Username]),
            )
            AccountField(
                value = state.domain,
                onValueChange = onDomainChange,
                label = stringResource(R.string.account_setup_domain_label),
                error = errorMessage(state.errors[SipAccountField.Domain]),
                keyboardType = KeyboardType.Uri,
            )
            PasswordField(
                value = state.password,
                onValueChange = onPasswordChange,
                error = errorMessage(state.errors[SipAccountField.Password]),
            )
            AccountField(
                value = state.outboundProxy,
                onValueChange = onOutboundProxyChange,
                label = stringResource(R.string.account_setup_outbound_proxy_label),
                error = errorMessage(state.errors[SipAccountField.OutboundProxy]),
                keyboardType = KeyboardType.Uri,
            )
            AccountField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = stringResource(R.string.account_setup_display_name_label),
                error = null,
            )

            TransportSelector(selected = state.transport, onSelect = onTransportChange)

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.account_setup_save_button))
            }

            if (state.saved) {
                Text(
                    text = stringResource(R.string.account_setup_saved_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.account_setup_save_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Resolves a validation [error] code to its localized inline message, or null when there's no error. */
@Composable
private fun errorMessage(error: SipAccountError?): String? = error?.let {
    stringResource(
        when (it) {
            SipAccountError.UsernameRequired -> R.string.account_setup_error_username_required
            SipAccountError.UsernameHasSpaces -> R.string.account_setup_error_username_spaces
            SipAccountError.UsernameHasDomain -> R.string.account_setup_error_username_has_domain
            SipAccountError.UsernameUnsupportedChars -> R.string.account_setup_error_username_unsupported
            SipAccountError.DomainRequired -> R.string.account_setup_error_domain_required
            SipAccountError.DomainInvalid -> R.string.account_setup_error_domain_invalid
            SipAccountError.PasswordRequired -> R.string.account_setup_error_password_required
            SipAccountError.OutboundProxyInvalid -> R.string.account_setup_error_proxy_invalid
        },
    )
}

@Composable
private fun AccountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, error: String?) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.account_setup_password_label)) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.account_setup_password_hide else R.string.account_setup_password_show,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportSelector(selected: SipTransport, onSelect: (SipTransport) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.account_setup_transport_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val options = SipTransport.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, transport ->
                SegmentedButton(
                    selected = transport == selected,
                    onClick = { onSelect(transport) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(transport.name.uppercase())
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountSetupPreview() {
    PhomoTheme {
        AccountSetupContent(
            state = AccountSetupUiState(username = "alice", domain = "sip.example.com", transport = SipTransport.Tls),
            onUsernameChange = {}, onDomainChange = {}, onPasswordChange = {},
            onOutboundProxyChange = {}, onDisplayNameChange = {}, onTransportChange = {}, onSave = {},
        )
    }
}
