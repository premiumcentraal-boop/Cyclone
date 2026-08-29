package com.cyclone.teamworksniper.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

data class CalendarShiftEvent(
    val date: LocalDate,
    val code: ShiftCode,
    val start: LocalTime?,
    val end: LocalTime?,
    val status: String,
)

class PhoneCalendarGateway(private val context: Context) {
    fun sync(events: List<CalendarShiftEvent>): Boolean = runCatching {
        val calendarId = calendarId() ?: return false
        clear(calendarId)
        val zone = TimeZone.getDefault().id
        events.forEach { event ->
            val start = event.start ?: return@forEach
            val end = event.end ?: start.plusHours(2)
            val startMs = event.date.atTime(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs = event.date.atTime(end).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val title = when (event.status) {
                "claimed" -> "Working · ${event.code.name}"
                "sniping" -> "Sniping · ${event.code.name}"
                else -> "Open · ${event.code.name}"
            }
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, "Teamwork Sniper · ${event.code.name} · ${event.status}")
                put(CalendarContract.Events.EVENT_TIMEZONE, zone)
                put(CalendarContract.Events.AVAILABILITY, if (event.status == "claimed") CalendarContract.Events.AVAILABILITY_BUSY else CalendarContract.Events.AVAILABILITY_FREE)
                put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }
        true
    }.getOrDefault(false)

    fun disconnect(): Boolean = runCatching {
        calendarId()?.let(::clear)
        true
    }.getOrDefault(false)

    private fun clear(calendarId: Long) {
        context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=?",
            arrayOf(calendarId.toString(), context.packageName),
        )
    }

    private fun calendarId(): Long? {
        val resolver = context.contentResolver
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF6500)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val inserted = resolver.insert(uri, values) ?: return null
        return ContentUris.parseId(inserted)
    }

    companion object {
        private const val ACCOUNT = "teamwork-sniper"
        private const val NAME = "Teamwork Sniper"
    }
}
