package com.focusguard.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focusguard.FocusGuardApp
import com.focusguard.R
import com.focusguard.scheduler.Assignment
import com.focusguard.ui.MainActivity
import com.focusguard.ui.ReminderActivity
import com.focusguard.ui.ReviewActivity
import java.time.format.DateTimeFormatter

/**
 * All user-facing reminders. One "task" reminder at a time (id fixed), replaced not stacked.
 * The task reminder is a full-screen intent: it appears over whatever the user is doing
 * and on the lock screen, without the app being open.
 */
object Reminders {
    private const val ID_TASK = 100
    private const val ID_PLAN = 101
    private const val ID_REVIEW = 102
    private const val ID_RESET = 103
    private const val ID_INFEASIBLE = 104
    private const val ID_COMMITMENT_OVER = 105
    private const val ID_COACH = 106

    private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun showTask(context: Context, a: Assignment) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val full = ReminderActivity.intent(context, a.task.id)
        val fullPi = PendingIntent.getActivity(
            context, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val until = a.slot.end.format(hm)
        val text = if (a.task.firstAction.isNotBlank()) a.task.firstAction
        else context.getString(R.string.reminder_default_action)
        val n = Notification.Builder(context, FocusGuardApp.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.reminder_title, a.task.title, until))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setContentIntent(fullPi)
            .setFullScreenIntent(fullPi, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(action(context, R.string.action_start, ActionReceiver.ACTION_START, a.task.id))
            .addAction(action(context, R.string.action_done, ActionReceiver.ACTION_DONE, a.task.id))
            .build()
        nm.notify(ID_TASK, n)
        // Also launch directly when possible: full-screen intents are only honoured while the
        // screen is off / locked; when the phone is in use we bring the activity up ourselves.
        runCatching { context.startActivity(full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun cancelTask(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_TASK)
    }

    fun showReset(context: Context, minutes: Int) {
        simple(context, ID_RESET,
            context.getString(R.string.reset_title, minutes),
            context.getString(R.string.reset_text, minutes),
            MainActivity.intent(context))
    }

    fun showPlanTomorrow(context: Context) {
        simple(context, ID_PLAN,
            context.getString(R.string.plan_title),
            context.getString(R.string.plan_text),
            MainActivity.intent(context).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TOMORROW))
    }

    fun showWeeklyReview(context: Context) {
        simple(context, ID_REVIEW,
            context.getString(R.string.review_title),
            context.getString(R.string.review_text),
            Intent(context, ReviewActivity::class.java))
    }

    fun showCommitmentOver(context: Context) {
        simple(context, ID_COMMITMENT_OVER,
            context.getString(R.string.commitment_over_title),
            context.getString(R.string.commitment_over_text),
            MainActivity.intent(context))
    }

    fun showInfeasible(context: Context, titles: List<String>) {
        if (titles.isEmpty()) {
            context.getSystemService(NotificationManager::class.java).cancel(ID_INFEASIBLE); return
        }
        simple(context, ID_INFEASIBLE,
            context.getString(R.string.infeasible_title),
            context.getString(R.string.infeasible_text, titles.joinToString(", ")),
            MainActivity.intent(context))
    }

    /** Planner reminder from config/plan.txt: a plain, dismissible notification (no alarm). */
    fun showPlan(context: Context, title: String, action: String) {
        val text = if (action.isNotBlank()) action else context.getString(R.string.reminder_default_action)
        simple(context, ID_COACH, title, text, Intent(context, com.focusguard.ui.MainActivity::class.java))
    }

    private fun simple(context: Context, id: Int, title: String, text: String, open: Intent) {
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, FocusGuardApp.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun action(context: Context, label: Int, action: String, taskId: Long): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), ActionReceiver.intent(context, action, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, context.getString(label), pi).build()
    }
}
