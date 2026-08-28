package com.cyclone.teamworksniper.rules
import com.cyclone.teamworksniper.data.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class RuleJsonTest{@Test fun roundTrip(){val r=ShiftRule("1","S1 → S2 → S3",RuleType.SEQUENCE,listOf(ShiftCode.S1,ShiftCode.S2,ShiftCode.S3),true,setOf(0,2),setOf(LocalDate.of(2026,9,2)),setOf(DayOfWeek.WEDNESDAY));assertEquals(listOf(r),RuleJson.decode(RuleJson.encode(listOf(r))))};@Test fun unknownCodeInvalidatesRule(){assertTrue(RuleJson.decode("""{"schemaVersion":1,"rules":[{"id":"bad","type":"EXACT","codes":["X9"]}]}""").isEmpty())}}
