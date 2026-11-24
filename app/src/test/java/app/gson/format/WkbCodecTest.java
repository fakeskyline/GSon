package app.gson.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class WkbCodecTest {
    @Test
    public void pointRoundTrips() throws Exception {
        JsonObject source = JsonParser.parseString("""
                {"type":"Point","coordinates":[30.5,50.45]}
                """).getAsJsonObject();
        JsonObject result = WkbCodec.decode(WkbCodec.encode(source));
        assertEquals("Point", result.get("type").getAsString());
        assertEquals(30.5, result.getAsJsonArray("coordinates").get(0).getAsDouble(), 0.000001);
        assertEquals(50.45, result.getAsJsonArray("coordinates").get(1).getAsDouble(), 0.000001);
    }

    @Test
    public void polygonWithHolesRoundTrips() throws Exception {
        String json = """
                {"type":"Polygon","coordinates":[
                  [[0,0],[10,0],[10,10],[0,10],[0,0]],
                  [[2,2],[4,2],[4,4],[2,4],[2,2]]
                ]}
                """;
        JsonObject result = WkbCodec.decode(WkbCodec.encode(JsonParser.parseString(json).getAsJsonObject()));
        assertEquals(2, result.getAsJsonArray("coordinates").size());
    }

    @Test
    public void multiGeometryAndZValuesRoundTrip() throws Exception {
        String json = """
                {"type":"MultiPoint","coordinates":[[1,2,3],[4,5,6]]}
                """;
        JsonObject result = WkbCodec.decode(WkbCodec.encode(JsonParser.parseString(json).getAsJsonObject()));
        assertEquals(2, result.getAsJsonArray("coordinates").size());

        com.google.gson.JsonArray firstPoint = result.getAsJsonArray("coordinates").get(0).getAsJsonArray();
        assertEquals(1, firstPoint.get(0).getAsInt());
        assertEquals(2, firstPoint.get(1).getAsInt());
        assertEquals(3, firstPoint.get(2).getAsInt());

        com.google.gson.JsonArray secondPoint = result.getAsJsonArray("coordinates").get(1).getAsJsonArray();
        assertEquals(4, secondPoint.get(0).getAsInt());
        assertEquals(5, secondPoint.get(1).getAsInt());
        assertEquals(6, secondPoint.get(2).getAsInt());
    }

    @Test
    public void truncatedWkbIsRejected() throws Exception {
        byte[] wkb = WkbCodec.encode(JsonParser.parseString("""
                {"type":"Point","coordinates":[1,2]}
                """).getAsJsonObject());
        byte[] truncated = new byte[wkb.length - 1];
        System.arraycopy(wkb, 0, truncated, 0, truncated.length);
        try {
            WkbCodec.decode(truncated);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("завершился"));
            return;
        }
        throw new AssertionError("Truncated WKB must fail.");
    }
}
