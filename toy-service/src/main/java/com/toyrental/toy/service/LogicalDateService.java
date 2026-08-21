package com.toyrental.toy.service;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Collection;
import com.toyrental.toy.couchbase.LogicalDateDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Every service must read "current date" through here rather than LocalDate.now(), so the
 * business calendar can be simulated/advanced independently of the wall clock.
 *
 * NOTE: CLAUDE.md's reference pseudocode caches this in Redis for 60s, but toy-service does not
 * declare a Redis dependency anywhere in its approved stack (only api-gateway does, for rate
 * limiting) — adding one is a stack change that needs sign-off first. This uses an equivalent
 * in-process 60s TTL cache instead; swap for Redis if multi-instance cache consistency becomes
 * a real requirement.
 */
@Slf4j
@Service
public class LogicalDateService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long CACHE_TTL_SECONDS = 60;

    private final Collection logicalDateCollection;

    private volatile LogicalDateDocument cachedDocument;
    private volatile Instant cachedAt = Instant.EPOCH;

    public LogicalDateService(Bucket logicalDateBucket) {
        this.logicalDateCollection = logicalDateBucket.defaultCollection();
    }

    public LocalDate getCurrentDate() {
        return resolve().currentDate;
    }

    public boolean isMonthEnd() {
        return resolve().isMonthEnd;
    }

    public boolean isOverdueCheckDay() {
        return resolve().isOverdueCheckDay;
    }

    private synchronized LogicalDateDocument resolve() {
        if (cachedDocument != null && ChronoUnit.SECONDS.between(cachedAt, Instant.now()) < CACHE_TTL_SECONDS) {
            return cachedDocument;
        }

        try {
            LogicalDateDocument document = logicalDateCollection
                    .get(LogicalDateDocument.DOCUMENT_ID)
                    .contentAs(LogicalDateDocument.class);
            cachedDocument = LogicalDateDocument.builder()
                    .currentDate(document.getCurrentDate())
                    .isMonthEnd(document.isMonthEnd())
                    .isOverdueCheckDay(document.isOverdueCheckDay())
                    .build();
            cachedAt = Instant.now();
        } catch (DocumentNotFoundException e) {
            log.warn("logical-date::current not found in Couchbase, falling back to LocalDate.now()");
            cachedDocument = fallback();
            cachedAt = Instant.now();
        } catch (RuntimeException e) {
            log.warn("Failed to read logical-date::current from Couchbase, falling back to LocalDate.now()", e);
            cachedDocument = fallback();
            cachedAt = Instant.now();
        }

        return cachedDocument;
    }

    private LogicalDateDocument fallback() {
        LocalDate today = LocalDate.now(IST);
        boolean monthEnd = today.equals(today.withDayOfMonth(today.lengthOfMonth()));
        return LogicalDateDocument.builder()
                .currentDate(today)
                .isMonthEnd(monthEnd)
                .isOverdueCheckDay(monthEnd)
                .build();
    }

}
