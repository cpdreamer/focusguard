package com.focusguard.ui

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import com.focusguard.R
import com.focusguard.core.Escrow
import com.focusguard.core.Graph
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant

/** Escrow seal dialog, shared by the setup screen (before commit) and the main menu. */
object EscrowUi {
    fun setupDialog(a: Activity, onSealed: () -> Unit = {}) {
        if (Graph.settings.isCommitmentActive(Instant.now()) && Escrow.isSealed) { Ui.toast(a, R.string.locked_toast); return }
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val account = EditText(a).apply { hint = a.getString(R.string.escrow_hint_account); setText(Graph.settings.escrowAccount) }
        val password = EditText(a).apply { hint = a.getString(R.string.escrow_hint_password) }
        val token = EditText(a).apply { hint = a.getString(R.string.escrow_hint_token) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(account); addView(password); addView(token)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.escrow_setup_title)
            .setMessage(a.getString(R.string.escrow_setup_text, Escrow.DELAY_HOURS))
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.escrow_seal) { _, _ ->
                val ok = Escrow.setup(account.text.toString(), password.text.toString(), token.text.toString())
                Ui.toast(a, if (ok) R.string.escrow_sealed_toast else R.string.escrow_setup_refused)
                if (ok) onSealed()
            }
            .show()
    }
}
