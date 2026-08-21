package com.toyrental.booking.util;

import java.util.UUID;

/**
 * Every id/event_id column in this project's schema is VARCHAR(36) — exactly the length of a
 * bare UUID string. CLAUDE.md's naming convention prefixes ids ("cust-{uuid}") but its own
 * examples ("cust-0091") are far shorter than a full UUID, and a prefix plus a full 36-character
 * UUID doesn't fit the column at all (found via a live "value too long for type character
 * varying(36)" failure on customer registration). Truncating to the UUID's first 8 hex
 * characters keeps ids short like the documented examples and leaves every prefix used in this
 * codebase with comfortable headroom under 36 characters.
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    public static String shortId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

}
