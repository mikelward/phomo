package dev.phomo.sip

import java.util.Base64

/**
 * Serializes a [SipAccount] to and from a compact string, so the encrypted
 * store only has to deal with opaque bytes.
 *
 * The format is a versioned, newline-separated list of Base64-encoded fields.
 * Base64 keeps every value — passwords with newlines, spaces, or arbitrary
 * Unicode — round-trip-safe without any escaping, and the leading magic/version
 * lets the format evolve. [decode] fails closed: any malformed or
 * wrong-version input returns `null` (treated as "no stored account") rather
 * than throwing, so a corrupted store can never crash account load.
 *
 * Pure and framework-independent — unit-tested without a device. (The
 * encryption around it lives in the Keystore-backed store and is device-only.)
 */
object SipAccountCodec {

    private const val HEADER = "phomo-account/1"
    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(account: SipAccount): String = buildString {
        appendLine(HEADER)
        appendLine(enc(account.username))
        appendLine(enc(account.domain))
        appendLine(enc(account.password))
        appendLine(encNullable(account.outboundProxy))
        appendLine(encNullable(account.displayName))
        append(account.transport.name)
    }

    fun decode(text: String): SipAccount? {
        val lines = text.split('\n')
        if (lines.size < 7 || lines[0].trimEnd('\r') != HEADER) return null
        return try {
            SipAccount(
                username = dec(lines[1]),
                domain = dec(lines[2]),
                password = dec(lines[3]),
                outboundProxy = decNullable(lines[4]),
                displayName = decNullable(lines[5]),
                transport = SipTransport.valueOf(lines[6].trimEnd('\r')),
            )
        } catch (_: IllegalArgumentException) {
            // Bad Base64, unknown transport, etc. — treat the store as empty.
            null
        }
    }

    private fun enc(value: String): String = b64Encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String): String = String(b64Decoder.decode(value.trimEnd('\r')), Charsets.UTF_8)

    // Nullable fields: "-" for null, otherwise "+" followed by the Base64 value.
    private fun encNullable(value: String?): String = if (value == null) "-" else "+" + enc(value)

    private fun decNullable(value: String): String? {
        val v = value.trimEnd('\r')
        return when {
            v == "-" -> null
            v.startsWith("+") -> dec(v.substring(1))
            else -> throw IllegalArgumentException("bad nullable field")
        }
    }
}
