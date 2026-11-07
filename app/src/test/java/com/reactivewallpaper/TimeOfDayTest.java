package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimeOfDayTest {
    @Test
    public void hoursFallInTheirParts() {
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(0));
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(5));
        assertEquals(TimeOfDay.MORNING, TimeOfDay.part(6));
        assertEquals(TimeOfDay.MORNING, TimeOfDay.part(11));
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.part(12));
        assertEquals(TimeOfDay.EVENING, TimeOfDay.part(18));
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(22));
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(23));
    }

    @Test
    public void unsetPartsCarryTheEarlierLook() {
        String[] dayAndNight = {"day", "", "", "night"};
        assertEquals("day", TimeOfDay.lookFor(dayAndNight, TimeOfDay.MORNING));
        assertEquals("day", TimeOfDay.lookFor(dayAndNight, TimeOfDay.AFTERNOON));
        assertEquals("day", TimeOfDay.lookFor(dayAndNight, TimeOfDay.EVENING));
        assertEquals("night", TimeOfDay.lookFor(dayAndNight, TimeOfDay.NIGHT));
        assertEquals("", TimeOfDay.lookFor(new String[] {"", "", "", ""}, TimeOfDay.EVENING));
        // Only a night look: it covers the whole day.
        assertEquals("night", TimeOfDay.lookFor(new String[] {"", "", "", "night"}, TimeOfDay.AFTERNOON));
    }
}
