package com.cyclone.teamworksniper.rules

import com.cyclone.teamworksniper.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

object RuleJson {
    const val SCHEMA_VERSION=1
    fun encode(rules:List<ShiftRule>):String=JSONObject().apply { put("schemaVersion",SCHEMA_VERSION); put("rules",JSONArray().apply { rules.forEach { r -> put(JSONObject().apply { put("id",r.id); put("name",r.name); put("type",r.type.name); put("enabled",r.enabled); put("codes",JSONArray(r.codes.map{it.name})); put("weekOffsets",JSONArray(r.weekOffsets.sorted())); put("dates",JSONArray(r.dates.sorted().map{it.toString()})); put("days",JSONArray(r.days.sortedBy{it.value}.map{it.name})) }) } }) }.toString()
    fun decode(raw:String?):List<ShiftRule> { if(raw.isNullOrBlank())return emptyList(); return runCatching { val root=JSONObject(raw); if(root.optInt("schemaVersion",-1)!=SCHEMA_VERSION)return@runCatching emptyList(); val arr=root.optJSONArray("rules")?:return@runCatching emptyList(); buildList { for(i in 0 until arr.length()) decodeRule(arr.optJSONObject(i)?:continue)?.let(::add) } }.getOrDefault(emptyList()) }
    private fun decodeRule(j:JSONObject):ShiftRule? { val id=j.optString("id").takeIf{it.isNotBlank()}?:return null; val type=runCatching{RuleType.valueOf(j.optString("type"))}.getOrNull()?:return null; val rawCodes=j.optJSONArray("codes"); val codes=rawCodes.strings().mapNotNull(ShiftCode::fromRaw); if(codes.isEmpty()||codes.size!=(rawCodes?.length()?:0))return null; return ShiftRule(id,j.optString("name",id),type,codes,j.optBoolean("enabled",true),j.optJSONArray("weekOffsets").ints(),j.optJSONArray("dates").strings().mapNotNull{runCatching{LocalDate.parse(it)}.getOrNull()}.toSet(),j.optJSONArray("days").strings().mapNotNull{runCatching{DayOfWeek.valueOf(it)}.getOrNull()}.toSet()) }
    private fun JSONArray?.strings():List<String> { if(this==null)return emptyList(); return buildList { for(i in 0 until length()) optString(i).takeIf{it.isNotBlank()}?.let(::add) } }
    private fun JSONArray?.ints():Set<Int> { if(this==null)return emptySet(); return buildSet { for(i in 0 until length()) add(optInt(i)) } }
}
