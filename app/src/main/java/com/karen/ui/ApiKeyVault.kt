package com.karen.ui

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

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun toJson(entries: Map<String, Pair<String, String>>): String = buildString {
        append("{\"v\":1,\"keys\":{")
        entries.entries.forEachIndexed { i, (id, pair) ->
            if (i > 0) append(",")
            append("\"${escape(id)}\":{\"key\":\"${escape(pair.first)}\",\"model\":\"${escape(pair.second)}\"}")
        }
        append("}}")
    }

    /** Minimal JSON reader for our own envelope (objects, strings only). */
    private fun parseKeys(json: String): Map<String, Pair<String, String>>? {
        return try {
        val keysIdx = json.indexOf("\"keys\"")
        if (keysIdx < 0) return null
        var i = json.indexOf('{', keysIdx + 6)
        if (i < 0) return null
        val out = linkedMapOf<String, Pair<String, String>>()
        i++ // past keys {
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun readStr(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    sb.append(json[i])
                    sb.append(json[i + 1])
                    i += 2
                } else {
                    sb.append(json[i])
                    i++
                }
            }
            if (i >= json.length) return null
            i++ // closing quote
            return unescape(sb.toString())
        }
        while (true) {
            skipWs()
            if (i >= json.length) return null
            if (json[i] == '}') break
            val id = readStr() ?: return null
            skipWs()
            if (i >= json.length || json[i] != ':') return null
            i++
            skipWs()
            if (i >= json.length || json[i] != '{') return null
            i++
            var key = ""
            var model = ""
            while (true) {
                skipWs()
                if (i < json.length && json[i] == '}') {
                    i++
                    break
                }
                val k = readStr() ?: return null
                skipWs()
                if (i >= json.length || json[i] != ':') return null
                i++
                val v = readStr() ?: return null
                if (k == "key") key = v else if (k == "model") model = v
                skipWs()
                if (i < json.length && json[i] == ',') i++
            }
            if (key.isNotBlank()) out[id] = key to model
            skipWs()
            if (i < json.length && json[i] == ',') i++
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
