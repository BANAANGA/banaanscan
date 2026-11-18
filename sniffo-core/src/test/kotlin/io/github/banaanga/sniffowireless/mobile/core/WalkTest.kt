package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The phone's own walk survey over its sensor token (server release 34.2, walk.py `/api/ingest/walk`). */
class WalkTest {
    // what GET /api/ingest/walk answers with an open survey (as tests/test_walk.py of the server produces it)
    private val reply = """{"surveys": [{"id": 7, "name": "Upstairs", "created": 1795000000.5, "closed": null, "points": 2},
        {"id": 3, "name": "Ground floor", "created": 1794000000.0, "closed": 1794001000.0, "points": 63}],
      "open": 7, "window": 10, "good": -67, "weak": -75,
      "floorplan": {"width": 1000, "height": 640, "updated": null, "image": "png"},
      "survey": {"id": 7, "name": "Upstairs", "width_m": 12.0, "open": true,
        "points": [{"id": 41, "x": 0.5, "y": 0.5, "aps": 3, "best": -58}, {"id": 42, "x": 0.9, "y": 0.1, "aps": 1, "best": null}],
        "analysis": {"dead": [{"x": 0.9, "y": 0.1, "best": null, "room": null}], "points_good": 1, "points": 2, "area_good": 70,
          "suggest": {"x": 0.81, "y": 0.2, "room": "Office", "fixes": 90, "area_after": 97, "model": {"a": -40.0, "n": 3.0, "fitted": false}}}},
      "now": {"aps": 4, "best_mine": -61, "open": true}}"""

    @Test fun `reads the state`() {
        val s = Walk.parse(reply)
        assertEquals(listOf(7L, 3L), s.surveys.map { it.id })
        assertFalse(s.surveys[0].closed)
        assertTrue(s.surveys[1].closed)
        assertEquals(7L, s.open)
        assertEquals(0.64, s.floor.ratio, 1e-9)
        assertTrue(s.floor.drawable)
        val v = s.survey!!
        assertEquals(12.0, v.widthM, 0.0)
        assertEquals(listOf(-58, null), v.points.map { it.best })
        assertEquals(1, v.analysis!!.dead)
        assertEquals("Office", v.analysis!!.suggest!!.room)
        assertEquals(Walk.Now(4, -61), s.now)
    }

    @Test fun `nothing yet, and an svg floor plan`() {
        val s = Walk.parse("""{"surveys": [], "open": null, "window": 10, "good": -67, "weak": -75, "survey": null,
            "floorplan": {"width": null, "height": null, "updated": null, "image": "svg"}}""")
        assertNull(s.survey)
        assertNull(s.now)
        assertFalse(s.floor.drawable)
        assertEquals(0.64, s.floor.ratio, 1e-9)
    }

    @Test fun `colours as on the web page`() {
        assertEquals(0xFF1FA463.toInt(), Walk.colour(-55))
        assertEquals(0xFF7BC043.toInt(), Walk.colour(-67))
        assertEquals(0xFFF4C20D.toInt(), Walk.colour(-70))
        assertEquals(0xFFF08A24.toInt(), Walk.colour(-82))
        assertEquals(Walk.TOO_WEAK, Walk.colour(-90))
        assertEquals(Walk.NONE, Walk.colour(null))
    }

    @Test fun `calls the sensor endpoints with the token`() {
        val calls = mutableListOf<String>()
        val c = Walk.Client({ Target("http://srv:8000/", "tok") }) { method, url, token, body ->
            calls += "$method $url $token ${body ?: ""}"
            when {
                url.endsWith("/api/ingest/walk") && method == "POST" -> 200 to """{"id": 9}""".toByteArray()
                url.endsWith("/point") -> 200 to """{"id": 50, "aps": 6}""".toByteArray()
                url.contains("/stop") -> 409 to """{"detail": "This survey is closed"}""".toByteArray()
                else -> 200 to reply.toByteArray()
            }
        }
        assertEquals(9L, c.start("Attic", 8.5))
        assertEquals(6, c.point(9, 1.4, 0.25))   // clamped to the floor
        assertEquals(7L, c.state(7).survey!!.id)
        c.undo(9, 50)
        try { c.stop(9); fail() } catch (e: IOException) { assertEquals("This survey is closed", e.message) }
        assertEquals(listOf(
            "POST http://srv:8000/api/ingest/walk tok {\"name\":\"Attic\",\"width_m\":8.5}",
            "POST http://srv:8000/api/ingest/walk/9/point tok {\"x\":1.0,\"y\":0.25}",
            "GET http://srv:8000/api/ingest/walk?survey=7 tok ",
            "DELETE http://srv:8000/api/ingest/walk/9/point/50 tok ",
            "POST http://srv:8000/api/ingest/walk/9/stop tok ",
        ), calls)
        try { Walk.Client({ null }).state(); fail() } catch (e: IOException) { assertTrue(e.message!!.startsWith("No server set")) }
    }
}
