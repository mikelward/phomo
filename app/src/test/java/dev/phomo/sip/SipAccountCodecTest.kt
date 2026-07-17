package dev.phomo.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipAccountCodecTest {

    private fun roundTrip(account: SipAccount) {
        assertEquals(account, SipAccountCodec.decode(SipAccountCodec.encode(account)))
    }

    @Test
    fun roundTrip_fullAccount() {
        roundTrip(
            SipAccount(
                username = "alice",
                domain = "sip.example.com",
                password = "s3cret",
                outboundProxy = "proxy.example.com:5061",
                displayName = "Alice Example",
                transport = SipTransport.Tls,
            ),
        )
    }

    @Test
    fun roundTrip_minimalAccount_nullsPreserved() {
        val account = SipAccount(username = "u", domain = "example.com", password = "p")
        val decoded = SipAccountCodec.decode(SipAccountCodec.encode(account))
        assertEquals(account, decoded)
        assertNull(decoded!!.outboundProxy)
        assertNull(decoded.displayName)
    }

    @Test
    fun roundTrip_awkwardPassword_survives() {
        // Newlines, spaces, delimiters, and Unicode must all survive Base64.
        for (pw in listOf("has spaces", "  leading/trailing  ", "new\nline", "+/=-#@:;", "pÄss wörd", "🔐emoji")) {
            roundTrip(SipAccount(username = "u", domain = "example.com", password = pw))
        }
    }

    @Test
    fun roundTrip_everyTransport() {
        for (t in SipTransport.entries) {
            roundTrip(SipAccount(username = "u", domain = "example.com", password = "p", transport = t))
        }
    }

    @Test
    fun encode_isDeterministic() {
        val account = SipAccount(username = "u", domain = "example.com", password = "p")
        assertEquals(SipAccountCodec.encode(account), SipAccountCodec.encode(account))
    }

    @Test
    fun decode_failsClosed_onMalformedInput() {
        for (bad in listOf(
            "",
            "not-the-header\nstuff",
            "phomo-account/1", // header only, missing fields
            "phomo-account/1\n!!!notbase64!!!\nx\nx\n-\n-\nTls", // bad base64
            "phomo-account/999\na\nb\nc\n-\n-\nTls", // wrong version
        )) {
            assertNull("expected decode('$bad') to be null", SipAccountCodec.decode(bad))
        }
    }

    @Test
    fun decode_failsClosed_onUnknownTransport() {
        val text = SipAccountCodec.encode(SipAccount(username = "u", domain = "example.com", password = "p"))
            .replaceAfterLast('\n', "Carrier")
        assertNull(SipAccountCodec.decode(text))
    }

    @Test
    fun decode_toleratesCrLfLineEndings() {
        val account = SipAccount(username = "u", domain = "example.com", password = "p", transport = SipTransport.Udp)
        val crlf = SipAccountCodec.encode(account).replace("\n", "\r\n")
        assertEquals(account, SipAccountCodec.decode(crlf))
    }
}
