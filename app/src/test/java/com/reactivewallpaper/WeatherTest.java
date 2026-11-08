package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class WeatherTest {
    @Test
    public void readsOpenMeteoCurrentWeather() throws Exception {
        JSONObject response = new JSONObject("{\"current\":{\"time\":\"2026-11-08T12:00\",\"weather_code\":63,"
                + "\"precipitation\":1.2,\"wind_speed_10m\":25.0,\"wind_direction_10m\":270}}");
        Weather.State s = Weather.parse(response);
        assertEquals(Weather.RAIN, s.kind);
        assertEquals(0.6f, s.intensity, 0.01f);
        // A west wind blows the rain toward the east: to the right.
        assertTrue("wind " + s.wind, s.wind > 0.4f);
    }

    @Test
    public void weatherCodes() {
        assertEquals(Weather.CLEAR, Weather.kindOf(0));
        assertEquals(Weather.CLOUDY, Weather.kindOf(3));
        assertEquals(Weather.FOG, Weather.kindOf(45));
        assertEquals(Weather.RAIN, Weather.kindOf(51));
        assertEquals(Weather.RAIN, Weather.kindOf(81));
        assertEquals(Weather.SNOW, Weather.kindOf(73));
        assertEquals(Weather.SNOW, Weather.kindOf(86));
        assertEquals(Weather.STORM, Weather.kindOf(95));
        assertEquals(1f, Weather.intensityOf(65, 20), 0.001f);
    }
}
