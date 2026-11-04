package com.example.platform.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class Times {
    /** Fixed width, because stored timestamps are compared as text. */
    private static final DateTimeFormatter STORED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {}

    public static String stored(Instant moment) {
        return STORED.format(moment);
    }

    /** The API's date-time format: whole seconds in UTC. */
    public static String api(Instant moment) {
        return moment.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
