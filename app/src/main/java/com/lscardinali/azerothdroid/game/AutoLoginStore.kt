package com.lscardinali.azerothdroid.game

import android.content.Context

/**
 * The account the game logs into by itself, with its password. The realm keeps only SRP6
 * verifiers, so the password has to be kept here to type it: in the app's private storage, which
 * only this app can read, and only for an account on this phone's own realm.
 */
class AutoLoginStore(context: Context) {

    data class Login(val account: String, val password: String)

    private val prefs = context.applicationContext.getSharedPreferences("auto_login", Context.MODE_PRIVATE)

    fun get(): Login? {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return Login(account, password)
    }

    fun set(account: String, password: String) {
        prefs.edit().putString(KEY_ACCOUNT, account.uppercase()).putString(KEY_PASSWORD, password).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_PASSWORD = "password"
    }
}
