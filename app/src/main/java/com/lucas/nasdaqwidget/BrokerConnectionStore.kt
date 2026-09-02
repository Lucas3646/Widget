package com.lucas.nasdaqwidget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BrokerConnectionStore {
    private const val PREFS = "broker_connections"
    private const val KEY_ALIAS = "market_widgets_broker_key"

    data class KrakenCredentials(val apiKey: String, val apiSecret: String)
    data class IbkrFlexCredentials(val token: String, val queryId: String)

    fun saveKraken(context: Context, apiKey: String, apiSecret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kraken_key", encrypt(apiKey.trim()))
            .putString("kraken_secret", encrypt(apiSecret.trim()))
            .putBoolean("kraken_verified", false)
            .apply()
    }

    fun krakenCredentials(context: Context): KrakenCredentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("kraken_key", null) ?: return null
        val secret = prefs.getString("kraken_secret", null) ?: return null
        return runCatching { KrakenCredentials(decrypt(key), decrypt(secret)) }.getOrNull()
    }

    fun hasKraken(context: Context): Boolean = krakenCredentials(context) != null
    fun isKrakenVerified(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("kraken_verified", false)
    fun setKrakenVerified(context: Context, verified: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("kraken_verified", verified).apply() }
    fun clearKraken(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("kraken_key").remove("kraken_secret").remove("kraken_verified").apply() }

    fun saveIbkrFlex(context: Context, token: String, queryId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ibkr_flex_token", encrypt(token.trim()))
            .putString("ibkr_flex_query", encrypt(queryId.trim()))
            .putBoolean("ibkr_verified", false)
            .putBoolean("ibkr_setup", true)
            .apply()
    }

    fun ibkrFlexCredentials(context: Context): IbkrFlexCredentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("ibkr_flex_token", null) ?: return null
        val query = prefs.getString("ibkr_flex_query", null) ?: return null
        return runCatching { IbkrFlexCredentials(decrypt(token), decrypt(query)) }.getOrNull()
    }

    fun hasIbkrSetup(context: Context): Boolean = ibkrFlexCredentials(context) != null
    fun isIbkrVerified(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ibkr_verified", false)
    fun setIbkrVerified(context: Context, verified: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ibkr_verified", verified).apply() }
    fun setIbkrSetupAcknowledged(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ibkr_setup", value).apply() }
    fun clearIbkr(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ibkr_flex_token").remove("ibkr_flex_query").remove("ibkr_verified").remove("ibkr_setup").apply() }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }
}
