package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.ShiftCode
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class TeamworkParserTest{private val p=TeamworkParser(LocalDate.of(2026,8,28));private fun row(code:String,time:String,open:Boolean=true)=SemanticNode(clickable=true,actions=setOf("ACTION_CLICK"),children=listOf(SemanticNode(text=code),SemanticNode(text=time),SemanticNode(text=if(open)"Open to take" else "Unavailable")))
@Test fun normalization(){val r=SemanticNode(children=listOf(SemanticNode(text="Monday 31 August"),row("S1","14:00 - 16:00")));val s=p.parse(r).shifts.single().shift;assertEquals(LocalDate.of(2026,8,31),s.date);assertEquals(ShiftCode.S1,s.code);assertEquals(LocalTime.of(14,0),s.startTime);assertEquals(LocalTime.of(16,0),s.endTime)}
@Test fun dateHeaderAssociation(){val r=SemanticNode(children=listOf(SemanticNode(text="2026-09-01"),row("M1","08:30 - 10:30")));assertEquals(LocalDate.of(2026,9,1),p.parse(r).shifts.single().shift.date)}
@Test fun closedIgnored(){assertTrue(p.parse(SemanticNode(children=listOf(SemanticNode(text="2026-08-28"),row("S1","14:00 - 16:00",false)))).shifts.isEmpty())}
@Test fun unknownFailsClosed(){val r=p.parse(SemanticNode(children=listOf(SemanticNode(text="2026-08-28"),row("X9","14:00 - 16:00"))));assertTrue(r.shifts.isEmpty());assertEquals(1,r.ignoredOpenMarkers)}
@Test fun missingDateFailsClosed(){assertTrue(p.parse(SemanticNode(children=listOf(row("S3","18:00 - 20:00")))).shifts.isEmpty())}
@Test fun duplicateObservationsNormalizeOnce(){val duplicate=SemanticNode(text="S1 Open to take 14:00 - 16:00",clickable=true,children=listOf(SemanticNode(text="Open to take")));val r=p.parse(SemanticNode(children=listOf(SemanticNode(text="2026-08-28"),duplicate)));assertEquals(2,r.openMarkersSeen);assertEquals(1,r.shifts.size)}
@Test fun dutchMonth(){val s=p.parse(SemanticNode(children=listOf(SemanticNode(text="maandag 31 augustus"),row("M2","10.00 – 12.00")))).shifts.single().shift;assertEquals(LocalDate.of(2026,8,31),s.date)}
}
