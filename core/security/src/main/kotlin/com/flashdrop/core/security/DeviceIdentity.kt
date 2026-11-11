package com.flashdrop.core.security

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

object Fingerprints {
    /** SHA-256 of the certificate's SubjectPublicKeyInfo: identifies the key, not the certificate. */
    fun of(certificate: Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Short human-readable form, e.g. "A1B2 C3D4 E5F6 0718". */
    fun display(bytes: ByteArray): String =
        bytes.take(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
}

/**
 * The device's long-term identity: an EC P-256 key pair generated inside the Android Keystore.
 * The private key never leaves the Keystore; peers know this device by the public-key fingerprint.
 */
@Singleton
class DeviceIdentity @Inject constructor() {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    private val entry: Pair<PrivateKey, X509Certificate> by lazy {
        if (!keyStore.containsAlias(ALIAS)) generate()
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        key to certificate
    }

    val certificate: X509Certificate get() = entry.second
    val fingerprint: ByteArray by lazy { Fingerprints.of(certificate) }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal("CN=FlashDrop Device"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 25 * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /**
     * TLS 1.3 context presenting this device's Keystore identity.
     *
     * Certificates are self-signed, so the chain is not validated here. Authenticity comes from
     * FDTP pairing instead: the peer's key fingerprint (proved by the TLS handshake) is either
     * already trusted or confirmed by the users comparing a short code bound to both fingerprints.
     */
    fun sslContext(): SSLContext {
        val (key, cert) = entry
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(SingleKeyManager(key, cert)), arrayOf(PinnedLaterTrustManager()), SecureRandom())
        }
    }

    private class SingleKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    /** Accepts any certificate; the session layer authenticates the presented key afterwards. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class PinnedLaterTrustManager : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "flashdrop_identity_v1"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
