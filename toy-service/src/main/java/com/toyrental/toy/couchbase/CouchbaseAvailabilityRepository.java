package com.toyrental.toy.couchbase;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
public class CouchbaseAvailabilityRepository {

    private final Collection availabilityCollection;

    public CouchbaseAvailabilityRepository(Bucket availabilityBucket) {
        this.availabilityCollection = availabilityBucket.defaultCollection();
    }

    public Optional<ToyAvailabilityDocument> findByToyId(String toyId) {
        try {
            ToyAvailabilityDocument document = availabilityCollection
                    .get(ToyAvailabilityDocument.documentId(toyId))
                    .contentAs(ToyAvailabilityDocument.class);
            return Optional.of(document);
        } catch (DocumentNotFoundException e) {
            return Optional.empty();
        } catch (CouchbaseException e) {
            // Broadened beyond DocumentNotFoundException after finding live (K8s deployment
            // with Couchbase down) that a genuine connectivity failure — a timeout trying to
            // reach the cluster at all, not "document doesn't exist" — threw uncaught here and
            // took the whole /availability endpoint down with a 500. AvailabilityService's
            // callers already treat an empty Optional as "fully available" by design; a
            // Couchbase outage should degrade the same way, not crash the request.
            log.warn("Couchbase unavailable while reading availability doc for toyId={}, treating as absent: {}",
                    toyId, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(ToyAvailabilityDocument document) {
        availabilityCollection.upsert(document.getId(), document);
        log.debug("Upserted Couchbase availability document id={}", document.getId());
    }

}
