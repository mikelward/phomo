package dev.phomo.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipAccountValidatorTest {

    @Test
    fun validInput_buildsAccount_withTrimmingAndDefaults() {
        val result = SipAccountValidator.validate(
            username = "  alice ",
            domain = " sip.example.com ",
            password = "s3cret",
        )
        assertTrue(result.isValid)
        val account = result.account!!
        assertEquals("alice", account.username)
        assertEquals("sip.example.com", account.domain)
        assertEquals("s3cret", account.password)
        assertNull(account.outboundProxy)
        assertNull(account.displayName)
        assertEquals(SipTransport.Tls, account.transport)
        assertEquals("sip:alice@sip.example.com", account.identity)
        assertEquals("sip.example.com", account.registrarHost)
    }

    @Test
    fun outboundProxy_overridesRegistrarHost() {
        val account = SipAccountValidator.validate(
            username = "alice",
            domain = "example.com",
            password = "p",
            outboundProxy = "term.example.com:5061",
            displayName = "Alice",
        ).account!!
        assertEquals("term.example.com:5061", account.outboundProxy)
        assertEquals("term.example.com:5061", account.registrarHost)
        assertEquals("Alice", account.displayName)
    }

    @Test
    fun blankRequiredFields_reportPerFieldErrors() {
        val result = SipAccountValidator.validate(username = "  ", domain = "", password = "")
        assertFalse(result.isValid)
        assertNull(result.account)
        assertEquals(SipAccountError.UsernameRequired, result.errors[SipAccountField.Username])
        assertEquals(SipAccountError.DomainRequired, result.errors[SipAccountField.Domain])
        assertEquals(SipAccountError.PasswordRequired, result.errors[SipAccountField.Password])
    }

    @Test
    fun usernameWithDomain_isRejected() {
        val result = SipAccountValidator.validate("alice@example.com", "example.com", "p")
        assertEquals(SipAccountError.UsernameHasDomain, result.errors[SipAccountField.Username])
    }

    @Test
    fun usernameWithSpace_isRejected() {
        val result = SipAccountValidator.validate("al ice", "example.com", "p")
        assertEquals(SipAccountError.UsernameHasSpaces, result.errors[SipAccountField.Username])
    }

    @Test
    fun usernameWithUriDelimiters_isRejected() {
        for (bad in listOf("alice#1", "alice%zz", "alice:1", "a/b", "a;b", "a?b", "a&b", "bob<x>")) {
            val result = SipAccountValidator.validate(bad, "example.com", "p")
            assertEquals(
                "expected '$bad' to be rejected",
                SipAccountError.UsernameUnsupportedChars,
                result.errors[SipAccountField.Username],
            )
        }
    }

    @Test
    fun usernameSafeCharacters_areAccepted() {
        for (good in listOf("alice", "alice.bob", "alice_1", "+15551234567", "a-b~c")) {
            val result = SipAccountValidator.validate(good, "example.com", "p")
            assertFalse("expected '$good' to be accepted", result.errors.containsKey(SipAccountField.Username))
        }
    }

    @Test
    fun malformedDomain_isRejected() {
        for (bad in listOf("exa mple.com", "example..com", "-example.com", "http://example.com", "example.com:0", "example.com:70000")) {
            val result = SipAccountValidator.validate("alice", bad, "p")
            assertTrue("expected '$bad' to be rejected", result.errors.containsKey(SipAccountField.Domain))
        }
    }

    @Test
    fun validDomains_areAccepted() {
        for (good in listOf("example.com", "sip.example.co.uk", "a.b.c.d.example.com", "192.168.0.1", "host.example.com:5060")) {
            val result = SipAccountValidator.validate("alice", good, "p")
            assertFalse("expected '$good' to be accepted", result.errors.containsKey(SipAccountField.Domain))
        }
    }

    @Test
    fun nonAsciiDigitOrSignedPort_isRejected() {
        for (bad in listOf("example.com:+5061", "example.com:-1", "example.com: 5061", "example.com:50a", "example.com:")) {
            assertTrue("expected '$bad' to be rejected", SipAccountValidator.validate("alice", bad, "p").errors.containsKey(SipAccountField.Domain))
        }
        assertFalse(SipAccountValidator.validate("alice", "example.com:5061", "p").errors.containsKey(SipAccountField.Domain))
    }

    @Test
    fun underscoreHost_isRejected_hyphenAccepted() {
        // SIP URI hostnames disallow '_', though Guava's InternetDomainName is lenient.
        for (bad in listOf("sip_trunk.example.com", "a_b.example.com", "under_score.com:5060")) {
            assertTrue("expected '$bad' to be rejected", SipAccountValidator.validate("alice", bad, "p").errors.containsKey(SipAccountField.Domain))
        }
        assertFalse(SipAccountValidator.validate("alice", "sip-trunk.example.com", "p").errors.containsKey(SipAccountField.Domain))
    }

    @Test
    fun ipv6Literals_areRejected() {
        // v1 supports hostname / IPv4 registrars only; a bare IPv6 host would build
        // an invalid sip:user@2001:db8::1 identity, and bracketed IPv6 is also out of scope.
        for (bad in listOf("2001:db8::1", "[2001:db8::1]", "[2001:db8::1]:5060", "::1", "fe80::1")) {
            assertTrue("expected IPv6 '$bad' to be rejected", SipAccountValidator.validate("alice", bad, "p").errors.containsKey(SipAccountField.Domain))
        }
    }

    @Test
    fun ipv4Octets_areRangeChecked() {
        // The last two use fullwidth (non-ASCII) digits, which must not be treated as a valid IPv4.
        for (bad in listOf("192.168.0.999", "256.0.0.1", "1.2.3.400", "999.999.999.999", "１２７.0.0.1", "127.0.0.１")) {
            assertTrue("expected IPv4 '$bad' to be rejected", SipAccountValidator.validate("alice", bad, "p").errors.containsKey(SipAccountField.Domain))
        }
        for (good in listOf("192.168.0.1", "255.255.255.255", "10.0.0.1", "0.0.0.0", "8.8.8.8:5061")) {
            assertFalse("expected IPv4 '$good' to be accepted", SipAccountValidator.validate("alice", good, "p").errors.containsKey(SipAccountField.Domain))
        }
    }

    @Test
    fun malformedOutboundProxy_isRejected_butEmptyIsFine() {
        assertTrue(SipAccountValidator.validate("alice", "example.com", "p", outboundProxy = "").isValid)
        val bad = SipAccountValidator.validate("alice", "example.com", "p", outboundProxy = "not a host")
        assertEquals(SipAccountError.OutboundProxyInvalid, bad.errors[SipAccountField.OutboundProxy])
    }

    @Test
    fun passwordSpaces_arePreservedNotTrimmed() {
        val account = SipAccountValidator.validate("alice", "example.com", "  spaced pw  ").account!!
        assertEquals("  spaced pw  ", account.password)
    }

    @Test
    fun allWhitespacePassword_isRejected() {
        val result = SipAccountValidator.validate("alice", "example.com", "   ")
        assertEquals(SipAccountError.PasswordRequired, result.errors[SipAccountField.Password])
        assertNull(result.account)
    }

    @Test
    fun toString_redactsPassword_keepsOtherFields() {
        val account = SipAccountValidator.validate("alice", "example.com", "sup3rSecret!", displayName = "Alice").account!!
        val text = account.toString()
        assertFalse("password must not appear in toString()", text.contains("sup3rSecret!"))
        assertTrue(text.contains("password=***"))
        assertTrue(text.contains("username=alice"))
        assertTrue(text.contains("domain=example.com"))
    }

    @Test
    fun transport_isCarriedThrough() {
        val account = SipAccountValidator.validate("alice", "example.com", "p", transport = SipTransport.Udp).account!!
        assertEquals(SipTransport.Udp, account.transport)
    }
}
