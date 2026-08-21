package com.toyrental.toy.couchbase;

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
        }
    }

    public void save(ToyAvailabilityDocument document) {
        availabilityCollection.upsert(document.getId(), document);
        log.debug("Upserted Couchbase availability document id={}", document.getId());
    }

}
