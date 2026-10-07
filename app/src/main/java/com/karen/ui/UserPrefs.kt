package com.karen.ui

import android.content.Context
import android.content.SharedPreferences

/** Lightweight on-device profile store (name, age, usage type). */
object UserPrefs {
    private const val PREFS = "karen_user_prefs"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_NAME = "name"
    private const val KEY_AGE = "age"
    private const val KEY_TYPE = "type"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun save(ctx: Context, name: String, age: String, type: String) {
        prefs(ctx).edit()
            .putBoolean(KEY_ONBOARDED, true)
            .putString(KEY_NAME, name)
            .putString(KEY_AGE, age)
            .putString(KEY_TYPE, type)
            .apply()
    }

    fun name(ctx: Context): String = prefs(ctx).getString(KEY_NAME, "-") ?: "-"
    fun age(ctx: Context): String = prefs(ctx).getString(KEY_AGE, "-") ?: "-"
    fun type(ctx: Context): String = prefs(ctx).getString(KEY_TYPE, "-") ?: "-"

    private const val KEY_DEFAULT_EFFORT = "default_effort"
    fun defaultEffort(ctx: Context): String = prefs(ctx).getString(KEY_DEFAULT_EFFORT, "Medium") ?: "Medium"
    fun setDefaultEffort(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_DEFAULT_EFFORT, value).apply()
    }

    // Thermal guard: trip point that stops work. Defaults persist on-device.
    private const val KEY_THERMAL_GUARD = "thermal_guard"
    fun thermalGuard(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_THERMAL_GUARD, true)
    fun setThermalGuard(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_THERMAL_GUARD, enabled).apply()
    }

    private const val KEY_THERMAL_LIMIT = "thermal_limit_c"
    fun thermalLimitC(ctx: Context): Float = prefs(ctx).getFloat(KEY_THERMAL_LIMIT, 50f)
    fun setThermalLimitC(ctx: Context, valueCelsius: Float) {
        prefs(ctx).edit().putFloat(KEY_THERMAL_LIMIT, valueCelsius).apply()
    }
    private const val KEY_WORKSPACE_URI = "workspace_uri"
    fun workspaceUri(ctx: Context): String? = prefs(ctx).getString(KEY_WORKSPACE_URI, null)
    fun setWorkspaceUri(ctx: Context, uri: String?) {
        prefs(ctx).edit().putString(KEY_WORKSPACE_URI, uri).apply()
    }

    // Third-party cloud API keys — stored only in on-device SharedPreferences,
    // one entry per provider id. Never synced or uploaded by Karen itself.
    private const val KEY_API_PREFIX = "api_key_"
    fun apiKey(ctx: Context, providerId: String): String =
        prefs(ctx).getString(KEY_API_PREFIX + providerId, "") ?: ""

    fun saveApiKey(ctx: Context, providerId: String, key: String) {
        prefs(ctx).edit().putString(KEY_API_PREFIX + providerId, key).apply()
    }

    fun clearApiKey(ctx: Context, providerId: String) {
        prefs(ctx).edit().remove(KEY_API_PREFIX + providerId).apply()
    }

    fun projects(ctx: Context): List<Pair<String, String>> {
        return (prefs(ctx).getString("projects", "") ?: "")
            .split(";;")
            .filter { it.isNotBlank() }
            .map { it.split("||", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } }
    }

    fun saveProjects(ctx: Context, items: List<Pair<String, String>>) {
        prefs(ctx).edit()
            .putString("projects", items.joinToString(";;") { "${it.first}||${it.second}" })
            .apply()
    }

    fun models(ctx: Context): List<String> {
        return (prefs(ctx).getString("installed_models", "") ?: "")
            .split(";;")
            .filter { it.isNotBlank() }
    }

    fun saveModels(ctx: Context, items: List<String>) {
        prefs(ctx).edit().putString("installed_models", items.joinToString(";;")).apply()
    }
}
