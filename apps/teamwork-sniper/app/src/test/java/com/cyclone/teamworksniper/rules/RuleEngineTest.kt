package com.cyclone.teamworksniper.rules

import com.cyclone.teamworksniper.data.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RuleEngineTest{private val anchor=LocalDate.of(2026,8,24);private val engine=RuleEngine(anchor);private fun rule(t:RuleType,vararg c:ShiftCode)=ShiftRule("r-${t.name}-${c.joinToString()}","r",t,c.toList(),weekOffsets=setOf(0,1));private fun shift(d:LocalDate,c:ShiftCode,t:String)=OpenShift(d,code=c,startTime=LocalTime.parse(t),endTime=LocalTime.parse(t).plusHours(2),semanticIdentity="$d-$c-$t")
@Test fun singleExactShift(){assertEquals(1,engine.evaluate(listOf(rule(RuleType.EXACT,ShiftCode.S1)),listOf(shift(anchor,ShiftCode.S1,"14:00"))).matches.size)}
@Test fun multipleAllowedCodes(){val e=engine.evaluate(listOf(rule(RuleType.COMBINATION,ShiftCode.M1,ShiftCode.M2)),listOf(shift(anchor,ShiftCode.M2,"10:00")));assertEquals(listOf(ShiftCode.M2),e.matches.single().shifts.map{it.code})}
@Test fun sequenceMatch(){val r=rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S2,ShiftCode.S3);assertEquals(1,engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.S1,"13:00"),shift(anchor,ShiftCode.S2,"15:00"),shift(anchor,ShiftCode.S3,"17:00"))).matches.size)}
@Test fun sequenceMissingMember(){val r=rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S2,ShiftCode.S3);assertTrue(engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.S1,"13:00"),shift(anchor,ShiftCode.S3,"17:00"))).matches.isEmpty())}
@Test fun wrongOrdering(){val r=rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S2,ShiftCode.S3);assertTrue(engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.S1,"15:00"),shift(anchor,ShiftCode.S2,"13:00"),shift(anchor,ShiftCode.S3,"17:00"))).matches.isEmpty())}
@Test fun differentDatesNeverBridge(){val r=rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S2,ShiftCode.S3);assertTrue(engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.S1,"13:00"),shift(anchor,ShiftCode.S2,"15:00"),shift(anchor.plusDays(1),ShiftCode.S3,"17:00"))).matches.isEmpty())}
@Test fun dayBoundaryNeverBridges(){val r=rule(RuleType.SEQUENCE,ShiftCode.M1,ShiftCode.M2);assertTrue(engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.M1,"22:00"),shift(anchor.plusDays(1),ShiftCode.M2,"00:30"))).matches.isEmpty())}
@Test fun weekDateDayFilters(){val d=anchor.plusWeeks(1).plusDays(2);val r=ShiftRule("f","f",RuleType.EXACT,listOf(ShiftCode.S1),weekOffsets=setOf(1),dates=setOf(d),days=setOf(d.dayOfWeek));assertEquals(d,engine.evaluate(listOf(r),listOf(shift(anchor,ShiftCode.S1,"12:00"),shift(d,ShiftCode.S1,"12:00"))).matches.single().date)}
@Test fun disabledRules(){assertTrue(engine.evaluate(listOf(rule(RuleType.EXACT,ShiftCode.S1).copy(enabled=false)),listOf(shift(anchor,ShiftCode.S1,"14:00"))).matches.isEmpty())}
@Test fun disarmedEvaluation(){val e=engine.evaluate(listOf(rule(RuleType.EXACT,ShiftCode.S1)),listOf(shift(anchor,ShiftCode.S1,"14:00")));assertEquals(ExecutionMode.DRY_RUN,SafetyGate.decide(SniperSettings(true,false),e))}
@Test fun disabledGlobalNeverClaims(){val e=engine.evaluate(listOf(rule(RuleType.EXACT,ShiftCode.S1)),listOf(shift(anchor,ShiftCode.S1,"14:00")));assertEquals(ExecutionMode.NO_ACTION,SafetyGate.decide(SniperSettings(false,true),e))}
@Test fun unknownCodeNeverMatches(){assertNull(ShiftCode.fromRaw("X9"))}
@Test fun nonConsecutiveSequenceRejected(){val e=engine.evaluate(listOf(rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S3)),listOf(shift(anchor,ShiftCode.S1,"13:00"),shift(anchor,ShiftCode.S3,"17:00")));assertTrue(e.matches.isEmpty())}
@Test fun nativeCombinedShiftMatchesExactSequence(){val combo=shift(anchor,ShiftCode.S1,"14:10").copy(codes=listOf(ShiftCode.S1,ShiftCode.S2,ShiftCode.S3));val e=engine.evaluate(listOf(rule(RuleType.SEQUENCE,ShiftCode.S1,ShiftCode.S2,ShiftCode.S3)),listOf(combo));assertEquals(listOf(combo),e.matches.single().shifts)}
}
