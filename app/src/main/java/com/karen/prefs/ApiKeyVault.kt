package com.karen.prefs

import com.karen.ui.cloudProviders

import android.content.Context
import android.net.Uri
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup for cloud API keys (+ model overrides):
 * PBKDF2-SHA256 (120k rounds) → AES-256-GCM, exported/imported as one
 * `.kbk` file through the system file picker. Portable across reinstalls
 * and devices — the password is never stored.
 *
 * File layout: magic "KBK2" (4B) + salt (16B) + IV (12B) + ciphertext(JSON).
 * JSON: {"v":1,"keys":{"<providerId>":{"key":"…","model":"…"}}}
 */
object ApiKeyVault {
    private const val MAGIC = "KBK2"
    private const val ITERS = 120000

    private fun keyFrom(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun secureRandomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        java.security.SecureRandom().nextBytes(b)
        return b
    }

    internal fun toJson(entries: Map<String, Pair<String, String>>): String {
        val root = org.json.JSONObject().put("v", 1)
        val keysObj = org.json.JSONObject()
        entries.forEach { (id, pair) ->
            keysObj.put(id, org.json.JSONObject().apply {
                put("key", pair.first)
                put("model", pair.second)
            })
        }
        root.put("keys", keysObj)
        return root.toString()
    }

    /** Parses the keys envelope using standard JSONObject. */
    internal fun parseKeys(json: String): Map<String, Pair<String, String>>? {
        return try {
            val root = org.json.JSONObject(json)
            val keysObj = root.optJSONObject("keys") ?: return null
            val out = linkedMapOf<String, Pair<String, String>>()
            val keys = keysObj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val entry = keysObj.optJSONObject(id)
                if (entry != null) {
                    val key = entry.optString("key", "")
                    val model = entry.optString("model", "")
                    if (key.isNotBlank()) {
                        out[id] = key to model
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Writes password-encrypted keys to [uri]. Returns a user message. */
    fun exportKeys(ctx: Context, uri: Uri, password: String): String {
        return try {
        if (password.length < 4) return "Password needs at least 4 characters"
        val entries = (cloudProviders + UserPrefs.customProviders(ctx)).mapNotNull { p ->
            val key = UserPrefs.apiKey(ctx, p.id)
            if (key.isBlank()) null else p.id to (key to UserPrefs.apiModel(ctx, p.id))
        }.toMap()
        if (entries.isEmpty()) return "No API keys stored to export"
        val salt = secureRandomBytes(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(password, salt))
        val cipherText = cipher.doFinal(toJson(entries).toByteArray(Charsets.UTF_8))
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(salt)
            out.write(cipher.iv)
            out.write(cipherText)
        } ?: return "Could not open file for writing"
        "Exported ${entries.size} key${if (entries.size == 1) "" else "s"} (password-encrypted)"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }

    /** Reads + decrypts [uri] into stored keys. Returns a user message. */
    fun importKeys(ctx: Context, uri: Uri, password: String): String {
        return try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return "Could not open file"
        if (bytes.size < 32 || String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) {
            return "Not a Karen key file"
        }
        val salt = bytes.copyOfRange(4, 20)
        val iv = bytes.copyOfRange(20, 32)
        val cipherText = bytes.copyOfRange(32, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
        val json = try {
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            return "Wrong password or damaged file"
        }
        val entries = parseKeys(json) ?: return "File contents unreadable"
        if (entries.isEmpty()) return "No keys found in file"
        var n = 0
        entries.forEach { (id, pair) ->
            if ((cloudProviders + UserPrefs.customProviders(ctx)).any { it.id == id }) {
                UserPrefs.saveApiKey(ctx, id, pair.first)
                UserPrefs.setApiModel(ctx, id, pair.second)
                n++
            }
        }
        if (n == 0) "No known providers in file" else "Imported $n key${if (n == 1) "" else "s"}"
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }
}
