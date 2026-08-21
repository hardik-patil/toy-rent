package com.toyrental.booking.couchbase;

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
        }
    }

    public void save(MonthlyReportDocument document) {
        reportsCollection.upsert(document.getId(), document);
        log.debug("Upserted Couchbase report document id={}", document.getId());
    }

}
