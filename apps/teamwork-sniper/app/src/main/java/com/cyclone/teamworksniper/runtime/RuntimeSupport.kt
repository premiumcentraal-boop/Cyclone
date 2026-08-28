package com.cyclone.teamworksniper.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import com.cyclone.teamworksniper.teamwork.SemanticNode
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import com.cyclone.teamworksniper.data.TriggerEvent

object TeamworkLauncher { const val PACKAGE="tech.picnic.workapp"; fun open(context:Context,pending:PendingIntent?=null):String { if(pending!=null)runCatching{pending.send()}.onSuccess{return "notification-pending-intent"}; val i=context.packageManager.getLaunchIntentForPackage(PACKAGE)?:return "launch-intent-unavailable"; i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); return runCatching{context.startActivity(i);"package-launch-intent"}.getOrElse{"launch-failed:${it::class.java.simpleName}"} } }
data class PermissionState(val notificationAccess:Boolean,val accessibilityAccess:Boolean){val ready get()=notificationAccess&&accessibilityAccess}
object PermissionChecker { fun read(context:Context)=PermissionState(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName), Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any{it.equals(ComponentName(context,TeamworkAccessibilityService::class.java).flattenToString(),true)}) }
object SniperCoordinator { private val pending=AtomicReference<TriggerEvent?>(null);private var ref:WeakReference<TeamworkAccessibilityService>?=null;@Synchronized fun attach(s:TeamworkAccessibilityService){ref=WeakReference(s);pending.get()?.let(s::requestEvaluation)};@Synchronized fun detach(s:TeamworkAccessibilityService){if(ref?.get()===s)ref=null};fun submit(t:TriggerEvent){pending.set(t);ref?.get()?.requestEvaluation(t)};fun current()=pending.get();fun consume(t:TriggerEvent){pending.compareAndSet(t,null)} }
object AccessibilitySemanticTree {
    fun snapshot(root:AccessibilityNodeInfo):SemanticNode { val children=buildList { for(i in 0 until root.childCount){val c=root.getChild(i)?:continue;try{add(snapshot(c))}finally{c.recycle()}} };return SemanticNode(root.text?.toString(),root.contentDescription?.toString(),root.viewIdResourceName,root.className?.toString(),root.isClickable,root.isScrollable,root.actionList.mapNotNull{a->a.label?.toString()?:when(a.id){AccessibilityNodeInfo.ACTION_CLICK->"ACTION_CLICK";AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->"ACTION_SCROLL_FORWARD";AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->"ACTION_SCROLL_BACKWARD";else->null}}.toSet(),children) }
    fun nodeAtPath(root:AccessibilityNodeInfo,path:List<Int>):AccessibilityNodeInfo? { var current=AccessibilityNodeInfo.obtain(root);path.forEach{i->val next=current.getChild(i)?:run{current.recycle();return null};current.recycle();current=next};return current }
    fun firstScrollable(root:AccessibilityNodeInfo,action:Int):AccessibilityNodeInfo? { if(root.isScrollable&&root.actionList.any{it.id==action})return AccessibilityNodeInfo.obtain(root);for(i in 0 until root.childCount){val c=root.getChild(i)?:continue;val f=try{firstScrollable(c,action)}finally{c.recycle()};if(f!=null)return f};return null }
    fun nearestClickable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? { var c:AccessibilityNodeInfo?=AccessibilityNodeInfo.obtain(node);repeat(8){val n=c?:return null;if(n.isClickable||n.actionList.any{it.id==AccessibilityNodeInfo.ACTION_CLICK})return n;val p=n.parent;n.recycle();c=p};c?.recycle();return null }
    fun findClickableByOwnText(root:AccessibilityNodeInfo,predicate:(String)->Boolean):AccessibilityNodeInfo? { val own=listOfNotNull(root.text?.toString(),root.contentDescription?.toString()).joinToString(" ").replace(Regex("\\s+")," ").trim();if(own.isNotBlank()&&predicate(own))nearestClickable(root)?.let{return it};for(i in 0 until root.childCount){val c=root.getChild(i)?:continue;val f=try{findClickableByOwnText(c,predicate)}finally{c.recycle()};if(f!=null)return f};return null }
}
