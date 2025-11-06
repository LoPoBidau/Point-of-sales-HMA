package com.example.pos_hma.utils

import android.content.Context
import androidx.core.content.edit

object PrintersPref {
    private const val PREF = "printer_prefs"
    private const val KEY_MAC = "printer_mac"

    fun saveMac(ctx: Context, mac: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { putString(KEY_MAC, mac) }

    fun getMac(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MAC, null)

    fun clearMac(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { remove(KEY_MAC) }
}
