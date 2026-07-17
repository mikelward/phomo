package dev.phomo.sip

/**
 * Persists the single SIP account Phomo registers with. The credentials never
 * leave the device: they're encrypted at rest (see [EncryptedSipAccountStore])
 * and the backing file is excluded from cloud backup and device-to-device
 * transfer (see `res/xml/data_extraction_rules.xml`).
 */
interface SipAccountStore {
    /** Persists [account], replacing any previously stored one. */
    fun save(account: SipAccount)

    /** Returns the stored account, or `null` if none is saved (or it can't be read). */
    fun load(): SipAccount?

    /** Removes the stored account and its encryption key. */
    fun clear()
}
