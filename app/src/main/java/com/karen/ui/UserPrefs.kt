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

    private const val KEY_WORKSPACE_URI = "workspace_uri"
    fun workspaceUri(ctx: Context): String? = prefs(ctx).getString(KEY_WORKSPACE_URI, null)
    fun setWorkspaceUri(ctx: Context, uri: String?) {
        prefs(ctx).edit().putString(KEY_WORKSPACE_URI, uri).apply()
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
