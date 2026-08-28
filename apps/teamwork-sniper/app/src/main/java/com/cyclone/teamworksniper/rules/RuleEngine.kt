package com.cyclone.teamworksniper.rules

import com.cyclone.teamworksniper.data.*
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class RuleEngine(private val anchorDate: LocalDate = LocalDate.now()) {
    fun evaluate(rules:List<ShiftRule>, openShifts:List<OpenShift>):EvaluationSummary {
        val matches=mutableListOf<RuleMatch>(); val evaluated=mutableListOf<String>(); val misses=mutableListOf<String>()
        rules.forEach { rule ->
            if (!rule.enabled) { misses += "${rule.id}:disabled"; return@forEach }
            evaluated += rule.id
            validate(rule)?.let { misses += "${rule.id}:$it"; return@forEach }
            var hit=false
            openShifts.filter { allows(rule,it.date) }.groupBy { it.date }.toSortedMap().forEach { (date, shifts) ->
                val selected=when(rule.type){ RuleType.EXACT->shifts.filter{it.codes==rule.codes}; RuleType.COMBINATION->shifts.filter{it.codes.size==1&&it.code in rule.codes}; RuleType.SEQUENCE->matchSequence(rule,shifts) }
                if (selected.isNotEmpty()) { hit=true; matches += RuleMatch(rule.id,rule.name,rule.type,date,selected.sortedBy { rule.codes.indexOf(it.code) }) }
            }
            if (!hit) misses += "${rule.id}:no-matching-open-shift"
        }
        return EvaluationSummary(matches,evaluated,misses)
    }
    private fun validate(rule:ShiftRule):String?=when { rule.codes.isEmpty()->"no-codes"; rule.codes.distinct().size!=rule.codes.size->"duplicate-codes"; rule.type==RuleType.EXACT&&rule.codes.size!=1->"exact-requires-one-code"; rule.type==RuleType.SEQUENCE&&rule.codes.size<2->"sequence-requires-two-codes"; rule.type==RuleType.SEQUENCE&&!rule.codes.zipWithNext().all{(a,b)->b.order==a.order+1}->"sequence-codes-not-consecutive"; else->null }
    private fun matchSequence(rule:ShiftRule, shifts:List<OpenShift>):List<OpenShift> { shifts.singleOrNull { it.codes==rule.codes }?.let{return listOf(it)}; val selected=shifts.filter { it.codes.size==1&&it.code in rule.codes }; if(rule.codes.any{c->selected.count{it.code==c}!=1}) return emptyList(); val ordered=if(selected.all{it.startTime!=null}) selected.sortedBy{it.startTime}else selected.sortedBy{it.code.order}; return if(ordered.map{it.code}==rule.codes) ordered else emptyList() }
    private fun allows(rule:ShiftRule,date:LocalDate):Boolean { if(rule.dates.isNotEmpty()&&date !in rule.dates)return false; if(rule.days.isNotEmpty()&&date.dayOfWeek !in rule.days)return false; if(rule.weekOffsets.isNotEmpty()&&weekOffset(date) !in rule.weekOffsets)return false; return true }
    private fun weekOffset(date:LocalDate):Int { val a=anchorDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); val t=date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); return ((t.toEpochDay()-a.toEpochDay())/7).toInt() }
}
enum class ExecutionMode { NO_ACTION, DRY_RUN, CLAIM }
object SafetyGate { fun decide(settings:SniperSettings, evaluation:EvaluationSummary):ExecutionMode=when { !settings.enabled->ExecutionMode.NO_ACTION; evaluation.matches.isEmpty()->ExecutionMode.NO_ACTION; !settings.armed->ExecutionMode.DRY_RUN; else->ExecutionMode.CLAIM } }
