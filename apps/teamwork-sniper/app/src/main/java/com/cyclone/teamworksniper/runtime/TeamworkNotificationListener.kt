package com.cyclone.teamworksniper.runtime

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.data.TriggerSource

class TeamworkNotificationListener:NotificationListenerService(){override fun onNotificationPosted(sbn:StatusBarNotification){if(sbn.packageName!=TeamworkLauncher.PACKAGE)return;val elapsed=SystemClock.elapsedRealtime();val title=sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString();val text=sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString();val launch=TeamworkLauncher.open(this,sbn.notification.contentIntent);SniperCoordinator.submit(TriggerEvent(TriggerSource.NOTIFICATION,System.currentTimeMillis(),elapsed,title,text,launch))}}
