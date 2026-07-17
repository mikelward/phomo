package dev.phomo.sip

import com.google.common.net.HostAndPort
import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName

/**
 * The SIP credentials Phomo registers with to place calls, as entered in
 * account setup (see `SPEC.md` → "Persistence"). The reference provider is a
 * Twilio SIP trunk, but nothing here is Twilio-specific.
 *
 * This is a plain value type with no secrets handling of its own — the
 * [password] is persisted only through the encrypted, backup-excluded account
 * store, never logged or backed up.
 */
data class SipAccount(
    val username: String,
    val domain: String,
    val password: String,
    /** Outbound proxy / termination host; when null, signaling goes to [domain]. */
    val outboundProxy: String? = null,
    /** Optional caller display name shown in the SIP `From`. */
    val displayName: String? = null,
    val transport: SipTransport = SipTransport.Tls,
) {
    /** The SIP identity address, e.g. `sip:alice@example.com`. */
    val identity: String get() = "sip:$username@$domain"

    /** The host signaling actually goes to — the outbound proxy if set, else the domain. */
    val registrarHost: String get() = outboundProxy ?: domain

    /**
     * Redacts [password] — the data-class default would print the credential,
     * which would leak it into any log line or crash report that stringifies
     * the account. [equals]/[hashCode]/[copy] still use every field.
     */
    override fun toString(): String =
        "SipAccount(username=$username, domain=$domain, password=***, " +
            "outboundProxy=$outboundProxy, displayName=$displayName, transport=$transport)"
}

/** SIP signaling transport. TLS is the default: it encrypts signaling and most trunks (incl. Twilio) support it. */
enum class SipTransport { Udp, Tcp, Tls }

/** The fields a validation error can attach to, so a form can show the message inline. */
enum class SipAccountField { Username, Domain, Password, OutboundProxy }

/**
 * A validation failure, independent of how it's worded. The validator stays a
 * pure, framework-free object, so it emits these codes rather than English
 * strings; the UI resolves each to a localized `strings.xml` message. Keeping
 * the copy out of the validator is also what lets the translation workflow find
 * and localize the error text.
 */
enum class SipAccountError {
    UsernameRequired,
    UsernameHasSpaces,
    UsernameHasDomain,
    UsernameUnsupportedChars,
    DomainRequired,
    DomainInvalid,
    PasswordRequired,
    OutboundProxyInvalid,
}

/** Result of validating account-setup input: field errors, and the built [account] when there are none. */
data class SipAccountValidation(
    val errors: Map<SipAccountField, SipAccountError>,
    val account: SipAccount?,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Pure validation of raw account-setup form input. Framework-independent and
 * exhaustively unit-tested — a malformed registrar or an empty password must be
 * caught here, before any registration attempt, rather than failing opaquely on
 * the network.
 */
object SipAccountValidator {

    // Conservative safe subset of the SIP user component. Excludes URI
    // delimiters (@ : # ? / ; & = %, whitespace, …) so `sip:$username@$domain`
    // can be built by interpolation without a delimiter breaking the identity
    // or a bare `%` being read as an invalid escape. Covers real-world
    // usernames (alphanumerics, . _ - + ~); an exotic username is rejected at
    // the form rather than failing opaquely at registration.
    private val SIP_USER = Regex("^[A-Za-z0-9._+~-]+$")

    fun validate(
        username: String,
        domain: String,
        password: String,
        outboundProxy: String = "",
        displayName: String = "",
        transport: SipTransport = SipTransport.Tls,
    ): SipAccountValidation {
        val u = username.trim()
        val d = domain.trim()
        val proxy = outboundProxy.trim()
        val name = displayName.trim()
        val errors = LinkedHashMap<SipAccountField, SipAccountError>()

        when {
            u.isEmpty() -> errors[SipAccountField.Username] = SipAccountError.UsernameRequired
            u.any { it.isWhitespace() } -> errors[SipAccountField.Username] = SipAccountError.UsernameHasSpaces
            u.contains('@') -> errors[SipAccountField.Username] = SipAccountError.UsernameHasDomain
            !SIP_USER.matches(u) -> errors[SipAccountField.Username] = SipAccountError.UsernameUnsupportedChars
        }

        when {
            d.isEmpty() -> errors[SipAccountField.Domain] = SipAccountError.DomainRequired
            !isValidHostPort(d) -> errors[SipAccountField.Domain] = SipAccountError.DomainInvalid
        }

        // Requiredness uses isBlank() so an all-whitespace password is rejected,
        // but the password itself is never trimmed — leading/trailing spaces can
        // be significant in a real credential.
        // TODO(reconsider): should the password be trimmed after all? Preserving
        // leading/trailing spaces treats them as significant, but in practice
        // they're more often accidental copy-paste artifacts that cause opaque
        // login failures. Revisit once real trunk credentials are tested on-device.
        if (password.isBlank()) errors[SipAccountField.Password] = SipAccountError.PasswordRequired

        if (proxy.isNotEmpty() && !isValidHostPort(proxy)) {
            errors[SipAccountField.OutboundProxy] = SipAccountError.OutboundProxyInvalid
        }

        val account = if (errors.isEmpty()) {
            SipAccount(
                username = u,
                domain = d,
                password = password,
                outboundProxy = proxy.ifEmpty { null },
                displayName = name.ifEmpty { null },
                transport = transport,
            )
        } else {
            null
        }
        return SipAccountValidation(errors, account)
    }

    /**
     * Validates a `host` or `host:port` registrar/proxy using Guava's hardened
     * parsers rather than a hand-rolled grammar: [HostAndPort] splits and
     * range-checks the port (and handles bracketed IPv6), and the host is
     * accepted only if it's a valid IP literal ([InetAddresses], which
     * range-checks IPv4 octets and rejects non-ASCII digits) or a syntactically
     * valid domain name ([InternetDomainName]).
     */
    private fun isValidHostPort(value: String): Boolean {
        // A trailing ':' is an empty port; HostAndPort would otherwise accept it
        // as a portless host, leaving the malformed ':' in the stored registrar.
        if (value.endsWith(":")) return false
        val hostAndPort = try {
            HostAndPort.fromString(value)
        } catch (_: IllegalArgumentException) {
            return false
        }
        // HostAndPort permits port 0; a usable SIP endpoint needs 1..65535.
        if (hostAndPort.hasPort() && hostAndPort.port !in 1..65535) return false
        val host = hostAndPort.host
        if (host.isEmpty()) return false
        // Require ASCII: SIP registrars are ASCII hostnames/IPs, and
        // InternetDomainName otherwise accepts internationalized (IDN) labels,
        // letting e.g. fullwidth-digit "１２７.0.0.1" masquerade as a hostname.
        if (host.any { it.code > 127 }) return false
        // Reject IPv6 literals: v1's target trunks (Twilio, …) are hostname-based,
        // and a bare IPv6 host would build an invalid `sip:user@2001:db8::1`
        // identity (IPv6 in a SIP URI must be bracketed). After HostAndPort
        // strips any brackets, an IPv6 host still contains ':'; a hostname/IPv4
        // never does.
        if (host.contains(':')) return false
        // Guava's InternetDomainName leniently allows '_' in labels, but SIP URI
        // hostnames (RFC 3261) don't — reject it so sip_trunk.example.com is
        // caught at the form rather than failing at registration. (IP literals
        // never contain '_'.)
        if (host.contains('_')) return false
        return InetAddresses.isInetAddress(host) || InternetDomainName.isValid(host)
    }
}
