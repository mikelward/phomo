package dev.phomo

import dev.phomo.sip.SipAccount
import dev.phomo.sip.SipAccountField
import dev.phomo.sip.SipAccountStore
import dev.phomo.sip.SipTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(var stored: SipAccount? = null) : SipAccountStore {
        override fun save(account: SipAccount) { stored = account }
        override fun load(): SipAccount? = stored
        override fun clear() { stored = null }
    }

    private fun viewModel(store: SipAccountStore) = AccountSetupViewModel(store, dispatcher)

    @Test
    fun save_invalidInput_populatesErrors_doesNotPersist() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        vm.onUsernameChange("alice") // domain and password left blank
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.errors.containsKey(SipAccountField.Domain))
        assertTrue(vm.state.value.errors.containsKey(SipAccountField.Password))
        assertFalse(vm.state.value.saved)
        assertNull(store.stored)
    }

    @Test
    fun save_validInput_persistsAndMarksSaved() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        vm.onUsernameChange("alice")
        vm.onDomainChange("sip.example.com")
        vm.onPasswordChange("s3cret")
        vm.onOutboundProxyChange("proxy.example.com:5061")
        vm.onTransportChange(SipTransport.Tcp)
        vm.save()
        advanceUntilIdle()

        val saved = store.stored!!
        assertEquals("alice", saved.username)
        assertEquals("sip.example.com", saved.domain)
        assertEquals("s3cret", saved.password)
        assertEquals("proxy.example.com:5061", saved.outboundProxy)
        assertEquals(SipTransport.Tcp, saved.transport)
        assertTrue(vm.state.value.saved)
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test
    fun editingField_clearsItsError() = runTest(dispatcher) {
        val vm = viewModel(FakeStore())
        vm.save() // everything blank → all required errors
        advanceUntilIdle()
        assertTrue(vm.state.value.errors.containsKey(SipAccountField.Username))

        vm.onUsernameChange("alice")
        assertFalse(vm.state.value.errors.containsKey(SipAccountField.Username))
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun existingAccount_isLoadedAndPrefilled() = runTest(dispatcher) {
        val store = FakeStore(
            SipAccount(
                username = "bob",
                domain = "example.com",
                password = "pw",
                outboundProxy = "p.example.com",
                displayName = "Bob",
                transport = SipTransport.Udp,
            ),
        )
        val vm = viewModel(store)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("bob", s.username)
        assertEquals("example.com", s.domain)
        assertEquals("pw", s.password)
        assertEquals("p.example.com", s.outboundProxy)
        assertEquals("Bob", s.displayName)
        assertEquals(SipTransport.Udp, s.transport)
    }

    @Test
    fun editingWhileSaveInFlight_doesNotShowStaleSaved() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        vm.onUsernameChange("alice")
        vm.onDomainChange("sip.example.com")
        vm.onPasswordChange("s3cret")
        vm.save() // save launched but the store write hasn't run yet
        // User edits a field before the in-flight save completes.
        vm.onPasswordChange("newpass")
        advanceUntilIdle()

        // The old snapshot was persisted, but the form must not claim "saved"
        // for the edited value the user is now looking at.
        assertFalse(vm.state.value.saved)
        assertEquals("newpass", vm.state.value.password)
        assertEquals("s3cret", store.stored!!.password)
    }

    @Test
    fun load_storeThrows_fallsBackToEmptyForm_doesNotCrash() = runTest(dispatcher) {
        val throwingStore = object : SipAccountStore {
            override fun save(account: SipAccount) {}
            override fun load(): SipAccount? = throw RuntimeException("keystore unavailable")
            override fun clear() {}
        }
        val vm = AccountSetupViewModel(throwingStore, dispatcher)
        advanceUntilIdle()

        assertEquals(AccountSetupUiState(), vm.state.value)
    }

    @Test
    fun backToBackSaves_latestCredentialsWin() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        vm.onUsernameChange("alice")
        vm.onDomainChange("sip.example.com")
        vm.onPasswordChange("first")
        vm.save()
        vm.onPasswordChange("second")
        vm.save()
        advanceUntilIdle()

        // The later save must be the one persisted, and the form must show saved.
        assertEquals("second", store.stored!!.password)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun save_storeThrows_setsSaveFailed_andEditClearsIt() = runTest(dispatcher) {
        val throwingStore = object : SipAccountStore {
            override fun save(account: SipAccount) = throw RuntimeException("keystore unavailable")
            override fun load(): SipAccount? = null
            override fun clear() {}
        }
        val vm = AccountSetupViewModel(throwingStore, dispatcher)
        vm.onUsernameChange("alice")
        vm.onDomainChange("sip.example.com")
        vm.onPasswordChange("s3cret")
        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.saved)
        assertTrue(vm.state.value.saveFailed)

        // Editing after a failed save clears the failure banner.
        vm.onPasswordChange("another")
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun editsBeforeLoadReturns_areNotClobbered() = runTest(dispatcher) {
        val store = FakeStore(SipAccount("bob", "example.com", "pw"))
        val vm = viewModel(store)
        // User starts typing before the async load completes.
        vm.onUsernameChange("alice")
        advanceUntilIdle()
        assertEquals("alice", vm.state.value.username)
    }
}
