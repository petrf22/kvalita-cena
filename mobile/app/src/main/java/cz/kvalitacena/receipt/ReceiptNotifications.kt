package cz.kvalitacena.receipt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.kvalitacena.MainActivity
import cz.kvalitacena.R

/**
 * Lokální notifikace „účtenka je zpracovaná" — bez push služby Google (appka běží i bez GMS)
 * a bez e-mailu: obsah nákupu nejde třetí straně ani do schránky (docs/soukromi.md, „Účtenky").
 * Text notifikace proto neříká nic o obsahu nákupu, jen že je co zkontrolovat.
 */
object ReceiptNotifications {
  private const val CHANNEL_ID = "receipts"
  private const val NOTIFICATION_ID = 4201

  /** Extra intentu s trasou, kterou MainActivity otevře (jen [isAllowedNotificationRoute]). */
  const val EXTRA_ROUTE = "notification_route"

  fun ensureChannel(context: Context) {
    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.receipt_notification_channel),
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply { description = context.getString(R.string.receipt_notification_channel_description) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun canPost(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun showProcessed(context: Context, unseenCount: Int, route: String) {
    if (!canPost(context)) return
    ensureChannel(context)
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(EXTRA_ROUTE, route)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_receipt)
      .setContentTitle(context.getString(R.string.receipt_notification_title))
      .setContentText(context.getString(R.string.receipt_notification_text, unseenCount))
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
      // Oprávnění mohl uživatel odebrat mezi kontrolou a zobrazením — notifikace je jen bonus.
    }
  }
}
