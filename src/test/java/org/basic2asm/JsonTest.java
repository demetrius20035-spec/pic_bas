package org.basic2asm;

import org.basic2asm.config.Json;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Tests for the in-tree JSON parser used by config-file loading. */
public class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    public void parsesObjectsArraysAndScalars() {
        String json = "{ \"chip\": \"PIC16F84A\", \"clock\": \"4MHz\", "
                + "\"comments\": true, \"baud\": 9600, \"pins\": [\"RB0\", \"RB1\"] }";
        Object root = Json.parse(json);
        assertTrue(root instanceof Map);
        Map<String, Object> m = (Map<String, Object>) root;
        assertEquals("PIC16F84A", m.get("chip"));
        assertEquals(Boolean.TRUE, m.get("comments"));
        assertEquals(9600.0, ((Number) m.get("baud")).doubleValue(), 0.0001);
        List<Object> pins = (List<Object>) m.get("pins");
        assertEquals(2, pins.size());
        assertEquals("RB1", pins.get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTrailingGarbage() {
        Json.parse("{} garbage");
    }
}
