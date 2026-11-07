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

    @Test
    public void customTimesSplitTheDay() {
        int[] starts = {7 * 60 + 30, 13 * 60, 19 * 60, 23 * 60};
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(7 * 60 + 29, starts));
        assertEquals(TimeOfDay.MORNING, TimeOfDay.part(7 * 60 + 30, starts));
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.part(13 * 60, starts));
        assertEquals(TimeOfDay.EVENING, TimeOfDay.part(22 * 60 + 59, starts));
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.part(23 * 60 + 30, starts));
        // Out-of-order times are pushed into order.
        int[] fixed = TimeOfDay.ordered(new int[] {600, 500, 1000, 900});
        assertEquals(601, fixed[1]);
        assertEquals(1001, fixed[3]);
    }

    @Test
    public void sunriseAndSunsetMatchPublishedTimes() {
        // New York, June 21 (day 172), EDT: sunrise 5:25, sunset 8:31 pm.
        int[] ny = TimeOfDay.sunriseSunset(40.71, -74.01, 172, -240);
        assertEquals(5 * 60 + 25, ny[0], 5);
        assertEquals(20 * 60 + 31, ny[1], 5);
        // London, December 21 (day 355), GMT: sunrise 8:04, sunset 3:53 pm.
        int[] london = TimeOfDay.sunriseSunset(51.51, -0.13, 355, 0);
        assertEquals(8 * 60 + 4, london[0], 5);
        assertEquals(15 * 60 + 53, london[1], 5);
        // Midsummer far north: the sun never sets, so the fixed times are used.
        assertEquals(null, TimeOfDay.sunriseSunset(78.2, 15.6, 172, 120));
        assertEquals(6 * 60, TimeOfDay.sunStarts(78.2, 15.6, 172, 120)[0]);
        // Following the sun in New York in June: evening an hour before sunset.
        int[] starts = TimeOfDay.sunStarts(40.71, -74.01, 172, -240);
        assertEquals(19 * 60 + 31, starts[TimeOfDay.EVENING], 5);
    }
}
