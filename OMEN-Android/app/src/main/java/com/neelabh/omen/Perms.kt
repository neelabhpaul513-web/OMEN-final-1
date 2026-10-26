package com.neelabh.omen

import android.Manifest
import android.os.Build

object Perms {
    fun list(): Array<String> = mutableListOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS
    ).apply { if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()
}
