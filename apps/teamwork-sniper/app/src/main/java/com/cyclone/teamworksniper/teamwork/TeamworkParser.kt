package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.OpenShift
import com.cyclone.teamworksniper.data.ShiftCode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.util.Locale

data class ParsedOpenShift(val shift: OpenShift, val observationPath: List<Int>)
data class ParseResult(val shifts: List<ParsedOpenShift>, val openMarkersSeen: Int, val ignoredOpenMarkers: Int)

class TeamworkParser(private val referenceDate: LocalDate = LocalDate.now()) {
    private val codeRegex = Regex("(?i)(?<![A-Z0-9])(M1|M2|S1|S2|S3)(?![A-Z0-9])")
    private val openRegex = Regex("(?i)\\bopen\\s+to\\s+take\\b")
    private val rangeRegex = Regex("(?i)(\\d{1,2})[:.](\\d{2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})[:.](\\d{2})")
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val numericDate = Regex("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})(?!\\d)")
    private val dayMonth = Regex("(?i)(?<!\\d)(\\d{1,2})\\s+([A-Za-zÀ-ÿ.]+)(?:\\s+(\\d{4}))?")
    private val monthDay = Regex("(?i)([A-Za-zÀ-ÿ.]+)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?")
    private val months = mapOf("jan" to Month.JANUARY,"january" to Month.JANUARY,"januari" to Month.JANUARY,"feb" to Month.FEBRUARY,"february" to Month.FEBRUARY,"februari" to Month.FEBRUARY,"mar" to Month.MARCH,"march" to Month.MARCH,"mrt" to Month.MARCH,"maart" to Month.MARCH,"apr" to Month.APRIL,"april" to Month.APRIL,"may" to Month.MAY,"mei" to Month.MAY,"jun" to Month.JUNE,"june" to Month.JUNE,"juni" to Month.JUNE,"jul" to Month.JULY,"july" to Month.JULY,"juli" to Month.JULY,"aug" to Month.AUGUST,"august" to Month.AUGUST,"augustus" to Month.AUGUST,"sep" to Month.SEPTEMBER,"sept" to Month.SEPTEMBER,"september" to Month.SEPTEMBER,"oct" to Month.OCTOBER,"okt" to Month.OCTOBER,"october" to Month.OCTOBER,"oktober" to Month.OCTOBER,"nov" to Month.NOVEMBER,"november" to Month.NOVEMBER,"dec" to Month.DECEMBER,"december" to Month.DECEMBER)

    fun parse(root: SemanticNode): ParseResult {
        var lastDate: LocalDate? = null; var markers = 0; var ignored = 0; val parsed = linkedMapOf<String, ParsedOpenShift>()
        root.flatten().forEach { ref ->
            parseDate(ref.node.ownSemanticText())?.let { lastDate = it }
            if (!openRegex.containsMatchIn(ref.node.ownSemanticText())) return@forEach
            markers++
            val row = selectRow(ref) ?: run { ignored++; return@forEach }
            val text = row.node.subtreeSemanticText()
            val codes = codeRegex.findAll(text).mapNotNull { ShiftCode.fromRaw(it.groupValues[1]) }.distinct().toList()
            if (codes.size != 1) { ignored++; return@forEach }
            val date = parseDate(text) ?: row.ancestors.asReversed().firstNotNullOfOrNull { parseDate(it.node.ownSemanticText()) } ?: lastDate
            if (date == null) { ignored++; return@forEach }
            val times = rangeRegex.find(text)?.let { m -> safeTime(m.groupValues[1],m.groupValues[2]) to safeTime(m.groupValues[3],m.groupValues[4]) } ?: (null to null)
            val id = identity(date,codes.single(),times.first,times.second,row.node)
            parsed.putIfAbsent(id, ParsedOpenShift(OpenShift(date=date,code=codes.single(),startTime=times.first,endTime=times.second,semanticIdentity=id), row.path))
        }
        return ParseResult(parsed.values.toList(), markers, ignored)
    }

    private fun selectRow(marker: SemanticRef): SemanticRef? = (listOf(marker) + marker.ancestors.asReversed()).mapIndexedNotNull { distance, ref ->
        val text = ref.node.subtreeSemanticText(); if (!openRegex.containsMatchIn(text) || text.length > 900) return@mapIndexedNotNull null
        val codes = codeRegex.findAll(text).map { it.groupValues[1].uppercase() }.distinct().toList(); if (codes.size != 1) return@mapIndexedNotNull null
        var score = 100-distance.coerceAtMost(80); if (ref.node.clickable || ref.node.actions.any { it.contains("CLICK",true) }) score += 60; if (parseDate(text)!=null) score += 10; ref to score
    }.maxByOrNull { it.second }?.first

    fun parseDate(text: String): LocalDate? {
        isoDate.find(text)?.let { m -> return runCatching { LocalDate.of(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt()) }.getOrNull() }
        numericDate.find(text)?.let { m -> return runCatching { LocalDate.of(m.groupValues[3].toInt(),m.groupValues[2].toInt(),m.groupValues[1].toInt()) }.getOrNull() }
        dayMonth.find(text)?.let { m -> val month = monthFor(m.groupValues[2]) ?: return@let; return runCatching { LocalDate.of(resolveYear(month,m.groupValues[3].toIntOrNull()),month,m.groupValues[1].toInt()) }.getOrNull() }
        monthDay.find(text)?.let { m -> val month = monthFor(m.groupValues[1]) ?: return@let; return runCatching { LocalDate.of(resolveYear(month,m.groupValues[3].toIntOrNull()),month,m.groupValues[2].toInt()) }.getOrNull() }
        return null
    }
    private fun safeTime(h:String,m:String): LocalTime? = runCatching { LocalTime.of(h.toInt(),m.toInt()) }.getOrNull()
    private fun monthFor(raw:String): Month? = months[raw.lowercase(Locale.ROOT).trimEnd('.')]
    private fun resolveYear(month:Month, explicit:Int?):Int { if (explicit!=null) return explicit; val d=LocalDate.of(referenceDate.year,month,1); return when { d.isBefore(referenceDate.minusMonths(6))->referenceDate.year+1; d.isAfter(referenceDate.plusMonths(6))->referenceDate.year-1; else->referenceDate.year } }
    private fun identity(date:LocalDate, code:ShiftCode, start:LocalTime?, end:LocalTime?, row:SemanticNode):String { val raw=listOf(date,code,start?:"",end?:"",row.resourceId?:"",row.className?:"",row.subtreeSemanticText().lowercase()).joinToString("|"); return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(12).joinToString("") { "%02x".format(it) } }
}
