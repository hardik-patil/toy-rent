package com.toyrental.booking.couchbase;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
public class CouchbaseReportRepository {

    private final Collection reportsCollection;

    public CouchbaseReportRepository(Bucket reportsBucket) {
        this.reportsCollection = reportsBucket.defaultCollection();
    }

    public Optional<MonthlyReportDocument> findByMonthAndYear(int month, int year) {
        try {
            MonthlyReportDocument document = reportsCollection
                    .get(MonthlyReportDocument.documentId(month, year))
                    .contentAs(MonthlyReportDocument.class);
            return Optional.of(document);
        } catch (DocumentNotFoundException e) {
            return Optional.empty();
        } catch (CouchbaseException e) {
            // Same class of gap found live in toy-service's CouchbaseAvailabilityRepository
            // this sprint: a genuine connectivity failure isn't a DocumentNotFoundException,
            // so it would otherwise propagate uncaught. The month-end trigger consumer's
            // idempotency check ("does this report already exist?") should treat "can't tell,
            // Couchbase is down" the same as "doesn't exist yet" rather than crash the consumer.
            log.warn("Couchbase unavailable while checking for existing report month={} year={}: {}",
                    month, year, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(MonthlyReportDocument document) {
        reportsCollection.upsert(document.getId(), document);
        log.debug("Upserted Couchbase report document id={}", document.getId());
    }

}
