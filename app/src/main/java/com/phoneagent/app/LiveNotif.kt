package com.phoneagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// The ongoing "Live" notification: tap = open Live screen, buttons = mute / hang up
object LiveNotif {

    fun build(ctx: Context, muted: Boolean): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("live", "المحادثة الصوتية", NotificationManager.IMPORTANCE_LOW))
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, LiveActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            fl)
        val mute = PendingIntent.getService(
            ctx, 2, Intent(ctx, VoiceService::class.java).setAction("MUTE"), fl)
        val stop = PendingIntent.getService(
            ctx, 3, Intent(ctx, VoiceService::class.java).setAction("STOP"), fl)
        return Notification.Builder(ctx, "live")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PhoneAgent Live")
            .setContentText(if (muted) "الميكروفون مكتوم" else "جارٍ الاستماع...")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_lock_silent_mode,
                if (muted) "إلغاء الكتم" else "كتم الصوت", mute).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "قطع الاتصال", stop).build())
            .setOngoing(true)
            .build()
    }
}
