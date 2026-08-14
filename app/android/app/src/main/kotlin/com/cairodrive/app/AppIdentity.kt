package com.cairodrive.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Resolves this INSTALLED app's own package name and signing-certificate
 * SHA-1 fingerprint, read from PackageManager at runtime.
 *
 * Google's Places API (New) enforces "Android apps" key restrictions by
 * checking the X-Android-Package / X-Android-Cert headers on every request.
 * The Android Maps SDK attaches these automatically; a raw REST call — which is
 * what GooglePlacesSearchProvider issues via package:http — does not, so
 * without this the request is rejected as unidentified, independent of
 * whatever app identity is actually allow-listed in Cloud Console.
 *
 * Read from the OS, never hardcoded: the signing certificate differs between a
 * local debug build and the CI-signed release build (see key.properties /
 * CAIRODRIVE_KEYSTORE_BASE64), and a hardcoded value would go stale the moment
 * either changes.
 */
object AppIdentity {
    data class Identity(val packageName: String, val certSha1: String)

    fun resolve(context: Context): Identity? {
        return try {
            val certBytes = signingCertBytes(context) ?: return null
            Identity(context.packageName, sha1Hex(certBytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun signingCertBytes(context: Context): ByteArray? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signingInfo = info.signingInfo ?: return null
            // A single-signer app (true for every build this project produces)
            // has its certificate in signingCertificateHistory; a multi-signer
            // app (certificate rotation) must use apkContentsSigners instead —
            // signingCertificateHistory is empty in that case.
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
    }

    /** Uppercase hex, no colons — the exact format Google's X-Android-Cert expects. */
    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
