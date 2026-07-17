package dev.phomo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.phomo.sip.SipAccount
import dev.phomo.sip.SipAccountError
import dev.phomo.sip.SipAccountField
import dev.phomo.sip.SipAccountStore
import dev.phomo.sip.SipAccountValidator
import dev.phomo.sip.SipTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable state of the account-setup form. */
data class AccountSetupUiState(
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val outboundProxy: String = "",
    val displayName: String = "",
    val transport: SipTransport = SipTransport.Tls,
    /** Per-field validation errors, populated only after a failed [AccountSetupViewModel.save]. */
    val errors: Map<SipAccountField, SipAccountError> = emptyMap(),
    /** True once the account has been validated and persisted. */
    val saved: Boolean = false,
    /** True when the last save passed validation but the store write failed. */
    val saveFailed: Boolean = false,
)

/**
 * Drives the account-setup form: holds the field state, validates it with
 * [SipAccountValidator], and persists a valid account through the injected
 * [SipAccountStore]. Load and save run on [ioDispatcher] (default IO) so the
 * Keystore encryption never blocks the main thread; the pure validation runs
 * inline. An existing saved account is loaded and pre-filled on construction.
 */
class AccountSetupViewModel(
    private val store: SipAccountStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountSetupUiState())
    val state: StateFlow<AccountSetupUiState> = _state.asStateFlow()

    // Serializes the store writes so a slower earlier save can't commit after a
    // later one and overwrite the prefs with stale credentials. The mutex is
    // fair (FIFO), so writes land in the order saves were launched — the latest
    // save always wins on disk.
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            // A corrupt or invalidated credential store must not crash the first
            // screen: fall back to an empty form the user can fill and save,
            // overwriting the bad state.
            val existing = try {
                withContext(ioDispatcher) { store.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            _state.update { current ->
                // Don't clobber edits the user made before the load returned.
                if (current != AccountSetupUiState()) current else existing.toUiState()
            }
        }
    }

    fun onUsernameChange(value: String) = clearFieldAndSet(SipAccountField.Username) { it.copy(username = value) }
    fun onDomainChange(value: String) = clearFieldAndSet(SipAccountField.Domain) { it.copy(domain = value) }
    fun onPasswordChange(value: String) = clearFieldAndSet(SipAccountField.Password) { it.copy(password = value) }
    fun onOutboundProxyChange(value: String) = clearFieldAndSet(SipAccountField.OutboundProxy) { it.copy(outboundProxy = value) }
    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, saved = false, saveFailed = false) }
    fun onTransportChange(transport: SipTransport) = _state.update { it.copy(transport = transport, saved = false, saveFailed = false) }

    /** Validates the form; on success persists the account and sets [AccountSetupUiState.saved]. */
    fun save() {
        val s = _state.value
        val result = SipAccountValidator.validate(
            username = s.username,
            domain = s.domain,
            password = s.password,
            outboundProxy = s.outboundProxy,
            displayName = s.displayName,
            transport = s.transport,
        )
        val account = result.account
        if (account == null) {
            _state.update { it.copy(errors = result.errors, saved = false, saveFailed = false) }
            return
        }
        _state.update { it.copy(saveFailed = false) }
        // Cancel any save still in flight: it's superseded by this one, so there's
        // no point spending another Keystore encrypt + disk write on it.
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // The Keystore/SharedPreferences write can throw (key generation or
            // decryption-state errors, storage I/O). Surface a save-failure
            // state instead of letting the exception crash the setup screen.
            val ok = try {
                saveMutex.withLock {
                    withContext(ioDispatcher) { store.save(account) }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.update { current ->
                // Only reflect the outcome if the form still holds exactly what
                // we tried to persist. If the user edited a field (or kicked off
                // another save with different values) while this write was in
                // flight, that edit already reset saved/saveFailed — don't
                // overwrite it with a stale result for values on the way out.
                when {
                    !current.matchesInput(s) -> current
                    ok -> current.copy(errors = emptyMap(), saved = true, saveFailed = false)
                    else -> current.copy(saved = false, saveFailed = true)
                }
            }
        }
    }

    private inline fun clearFieldAndSet(field: SipAccountField, transform: (AccountSetupUiState) -> AccountSetupUiState) {
        _state.update { transform(it).copy(errors = it.errors - field, saved = false, saveFailed = false) }
    }

    /** True when the editable input fields match [snapshot]; ignores [saved]/[errors]. */
    private fun AccountSetupUiState.matchesInput(snapshot: AccountSetupUiState): Boolean =
        username == snapshot.username &&
            domain == snapshot.domain &&
            password == snapshot.password &&
            outboundProxy == snapshot.outboundProxy &&
            displayName == snapshot.displayName &&
            transport == snapshot.transport

    private fun SipAccount.toUiState() = AccountSetupUiState(
        username = username,
        domain = domain,
        password = password,
        outboundProxy = outboundProxy.orEmpty(),
        displayName = displayName.orEmpty(),
        transport = transport,
    )
}
