package com.noteapp.security

interface DatabasePassphraseProvider {
    fun hasStoredPassphrase(): Boolean

    fun getOrCreatePassphrase(): ByteArray
}
