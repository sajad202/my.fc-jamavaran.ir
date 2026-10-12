package com.app.myjamavaran

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

object TokenSync {
    private const val PREFS = "appsaz"

    fun save(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("tk", token).putBoolean("ok", false).apply()
    }

    fun trySend(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val token = p.getString("tk", "") ?: return
        if (token.isBlank() || p.getBoolean("ok", false)) return
        Thread {
            try {
                val conn = URL(BuildConfig.REST_BASE + "api.php?action=register_token").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = org.json.JSONObject()
                    .put("token", token)
                    .put("device", Build.MODEL ?: "")
                    .put("key", BuildConfig.APP_KEY)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 200) p.edit().putBoolean("ok", true).apply()
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}