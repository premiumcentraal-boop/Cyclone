package com.cyclone.mobile

import com.cyclone.mobile.gesture.DrawShape
import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.HandGestureRequest
import com.cyclone.mobile.gesture.Handedness
import com.cyclone.mobile.gesture.HandsStyle
import com.cyclone.mobile.gesture.SeededGestureRng
import com.cyclone.mobile.gesture.TouchGestureKind
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 run 6: gesture requests name controls, are planned on the screen, and pass the approval check's words. */
class HandGestureToolsTest {
    private val viewport = GestureBounds(0f, 0f, 1080f, 2400f)

    private fun node(
        id: String,
        bounds: UiBounds,
        text: String = "",
        className: String = "android.widget.FrameLayout",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        path: String = "w1/0/$id",
    ) = UiNodeSnapshot(
        id = id, path = path, parentId = null, childIds = emptyList(), depth = path.count { it == '/' }, windowId = 1,
        className = className, role = "", text = text, contentDescription = "", resourceId = id,
        bounds = bounds, clickable = clickable, longClickable = false, editable = editable, scrollable = scrollable,
        enabled = true, selected = false, checked = false, checkable = false, focused = false, focusable = false,
        visibleToUser = true,
    )

    private val photo = node("photo", UiBounds(0, 400, 1080, 1600), text = "Photo", className = "android.widget.ImageView", clickable = true)
    private val file = node("file", UiBounds(60, 300, 1020, 420), text = "Holiday.pdf", clickable = true)
    private val trash = node("trash", UiBounds(800, 2000, 1040, 2160), text = "Trash", clickable = true)
    private val folder = node("folder", UiBounds(60, 900, 1020, 1020), text = "Documents", clickable = true)
    private val pad = node("signature_pad", UiBounds(60, 1200, 1020, 1700), className = "android.view.View")
    private val sketch = node("sketch_canvas", UiBounds(60, 1200, 1020, 1700), className = "com.example.SketchView")
    private val field = node("name", UiBounds(60, 200, 1020, 300), text = "Name", className = "android.widget.EditText", editable = true)

    private fun snapshot(vararg nodes: UiNodeSnapshot) =
        UiSnapshot("com.example", null, 1080, 2400, 0L, "fp", "agent",
            listOf(UiWindowSnapshot(1, "App", 1, 0, true, true, UiBounds(0, 0, 1080, 2400))), nodes.toList())

    private fun plan(tool: String, params: JSONObject, target: UiNodeSnapshot?, vararg nodes: UiNodeSnapshot): HandGestureTools.Plan {
        val shot = snapshot(*nodes)
        return HandGestureTools.plan(
            tool, params, shot, viewport, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(7),
            selector = target?.let { ElementSelector(resourceId = it.resourceId) },
            resolveTo = { sel -> shot.nodes.firstOrNull { it.resourceId == sel.resourceId } },
        )
    }

    private fun ready(plan: HandGestureTools.Plan) = (plan as HandGestureTools.Plan.Ready).prepared

    @Test fun `requests refuse raw screen points on every gesture tool`() {
        for (tool in HandGestureRequest.TOOLS.keys) {
            val (request, problem) = HandGestureRequest.parse(tool, JSONObject().put("x1", 10).put("shape", "circle").put("scale", 2))
            assertNull(request)
            assertTrue(problem!!.contains("not screen coordinates"))
        }
    }

    @Test fun `requests parse plain intents and reject bad ones`() {
        assertEquals(TouchGestureKind.PINCH, HandGestureRequest.parse("phone.pinch", JSONObject().put("zoom", "in")).first!!.kind)
        assertEquals(2.0, (HandGestureRequest.parse("phone.pinch", JSONObject().put("zoom", "in")).first as HandGestureRequest.Pinch).scale, 0.0)
        assertNull(HandGestureRequest.parse("phone.pinch", JSONObject().put("scale", 1.02)).first)
        assertNull(HandGestureRequest.parse("phone.pinch", JSONObject().put("scale", 8)).first)
        assertNull(HandGestureRequest.parse("phone.drag", JSONObject()).first)
        assertNull(HandGestureRequest.parse("phone.drag", JSONObject().put("toElementId", "e2").put("direction", "up")).first)
        assertNull(HandGestureRequest.parse("phone.drag", JSONObject().put("direction", "up").put("holdMs", 100)).first)
        assertNotNull(HandGestureRequest.parse("phone.drag", JSONObject().put("direction", "up").put("amount", "far")).first)
        assertNull(HandGestureRequest.parse("phone.draw", JSONObject().put("shape", "star")).first)
        assertNull(HandGestureRequest.parse("phone.draw", JSONObject().put("shape", "circle").put("strokes", JSONArray())).first)
        val strokes = JSONArray().put(JSONArray().put(JSONArray().put(0.1).put(0.2)).put(JSONObject().put("x", 0.8).put("y", 0.9)))
        val draw = HandGestureRequest.parse("phone.draw", JSONObject().put("strokes", strokes)).first as HandGestureRequest.Draw
        assertEquals(2, draw.strokes!!.single().size)
        val outside = JSONArray().put(JSONArray().put(JSONArray().put(0.1).put(0.2)).put(JSONArray().put(1.5).put(0.2)))
        assertNull(HandGestureRequest.parse("phone.draw", JSONObject().put("strokes", outside)).first)
    }

    @Test fun `a double tap is planned on the photo and judged by its own words`() {
        val prepared = ready(plan("phone.double_tap", JSONObject(), photo, photo))
        assertEquals(TouchGestureKind.DOUBLE_TAP, prepared.gesture.kind)
        assertEquals("photo", prepared.gateNodeId)
        assertTrue("Photo" in prepared.gateLabels)
        assertNull(GateClassifier.classify("phone.double_tap", prepared.gateLabels))
    }

    @Test fun `dropping a file on Trash is a delete the owner approves, dropping it in a folder is not`() {
        val toTrash = ready(plan("phone.drag", JSONObject().put("toSelector", JSONObject().put("resourceId", "trash")), file, file, trash))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.drag", toTrash.gateLabels))
        assertTrue(trash.bounds.let { b -> toTrash.gesture.strokes.single().motion.end.let { it.x >= b.left && it.x <= b.right && it.y >= b.top && it.y <= b.bottom } })
        val toFolder = ready(plan("phone.drag", JSONObject().put("toSelector", JSONObject().put("resourceId", "folder")), file, file, folder))
        assertNull(GateClassifier.classify("phone.drag", toFolder.gateLabels))
    }

    @Test fun `a stale target is refused before anything moves`() {
        val missing = plan("phone.double_tap", JSONObject(), photo /* not on the screen */, file)
        assertEquals(PhoneToolErrorCode.STALE_ELEMENT, (missing as HandGestureTools.Plan.Refused).code)
        val noDrop = plan("phone.drag", JSONObject().put("toSelector", JSONObject().put("resourceId", "trash")), file, file)
        assertEquals(PhoneToolErrorCode.STALE_ELEMENT, (noDrop as HandGestureTools.Plan.Refused).code)
    }

    @Test fun `a pinch with no control zooms the screen`() {
        val prepared = ready(plan("phone.pinch", JSONObject().put("zoom", "out"), null, photo))
        assertEquals(TouchGestureKind.PINCH, prepared.gesture.kind)
        assertEquals("screen", prepared.gateNodeId)
    }

    @Test fun `drawing happens only on a canvas, and every signature asks the owner`() {
        val fieldPlan = plan("phone.draw", JSONObject().put("shape", "check"), field, field)
        assertEquals(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, (fieldPlan as HandGestureTools.Plan.Refused).code)
        val listPlan = plan("phone.draw", JSONObject().put("shape", "check"), photo, photo)
        assertTrue(listPlan is HandGestureTools.Plan.Refused)
        val check = ready(plan("phone.draw", JSONObject().put("shape", "check"), sketch, sketch))
        assertEquals(TouchGestureKind.DRAW, check.gesture.kind)
        assertNull(GateClassifier.classify("phone.draw", check.gateLabels))
        // A signature pad, whatever is drawn on it, and a signature-style shape anywhere, are consent.
        val onPad = ready(plan("phone.draw", JSONObject().put("shape", "underline"), pad, pad))
        assertEquals(GateClass.GRANT, GateClassifier.classify("phone.draw", onPad.gateLabels))
        val signed = ready(plan("phone.draw", JSONObject().put("shape", "signature-style"), sketch, sketch))
        assertEquals(GateClass.GRANT, GateClassifier.classify("phone.draw", signed.gateLabels))
        assertEquals(DrawShape.SIGNATURE, DrawShape.parse("signature-style"))
    }

    @Test fun `evidence facts carry counts and ratios, never coordinates`() {
        val trace = HumanGestureDispatchTrace(
            com.cyclone.mobile.gesture.HumanizeProfile.NORMAL, "humanized_pinch", 500L, true,
            gesture = "pinch", facts = mapOf("achievedScale" to 2.0, "direction" to "in", "pressMs" to listOf(80L, 70L)),
        )
        val facts = HandGestureTools.facts(trace)
        assertEquals("pinch", facts.getString("gesture"))
        assertEquals(2.0, facts.getDouble("achievedScale"), 0.0)
        assertEquals(2, facts.getJSONArray("pressMs").length())
    }
}
