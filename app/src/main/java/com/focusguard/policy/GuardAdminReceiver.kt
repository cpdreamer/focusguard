package com.focusguard.policy

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class GuardAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "provisioning complete")
    }

    companion object {
        private const val TAG = "GuardAdmin"
        fun component(context: Context): ComponentName =
            ComponentName(context, GuardAdminReceiver::class.java)
    }
}
