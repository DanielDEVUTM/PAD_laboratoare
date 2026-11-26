package com.pad.lab3.sync;

import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class SyncManagerTest {

    private static String render(Bson filter) {
        return filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    }

    @Test
    void lastWriterWinsOnlyReplacesVersionsThatAreNotNewer() {
        String filter = render(SyncManager.filterFor(new Document("_id", "1").append("updatedAt", new Date(1000))));
        assertTrue(filter.contains("\"_id\""), filter);
        assertTrue(filter.contains("$lte"), filter);
    }

    @Test
    void documentWithoutTimestampIsMatchedById() {
        String filter = render(SyncManager.filterFor(new Document("_id", "1")));
        assertTrue(filter.contains("\"_id\""), filter);
        assertFalse(filter.contains("updatedAt"), filter);
    }

    @Test
    void unknownWarehouseHasNoStatus() {
        SyncManager sync = new SyncManager("http://localhost:1");
        assertNull(sync.status("wh-9"));
        assertTrue(sync.status().isEmpty());
    }
}
