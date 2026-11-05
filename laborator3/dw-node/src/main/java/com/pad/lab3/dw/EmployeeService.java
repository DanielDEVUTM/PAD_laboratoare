package com.pad.lab3.dw;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic. Thread safety is delegated to MongoDB (the driver is thread-safe and every
 * write is atomic at document level), so no in-process collection needs locking.
 */
@Service
public class EmployeeService {

    private final MongoTemplate mongo;

    public EmployeeService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public EmployeePage list(String id, String department, Instant updatedSince, int offset, int limit) {
        List<Criteria> cs = new ArrayList<>();
        if (id != null && !id.isBlank()) cs.add(Criteria.where("_id").is(id));
        if (department != null && !department.isBlank()) cs.add(Criteria.where("department").is(department));
        if (updatedSince != null) cs.add(Criteria.where("updatedAt").gt(updatedSince));
        Query filter = cs.isEmpty() ? new Query() : new Query(new Criteria().andOperator(cs));
        long total = mongo.count(filter, Employee.class);
        // numericOrdering: ids "2" < "10" (numeric-looking ids sort naturally, others lexicographically)
        Query page = Query.of(filter).with(Sort.by("_id")).skip(offset).limit(limit)
                .collation(Collation.of("en").numericOrdering(true));
        List<Employee> items = mongo.find(page, Employee.class);
        return new EmployeePage(total, offset, limit, items);
    }

    public Optional<Employee> find(String id) {
        return Optional.ofNullable(mongo.findById(id, Employee.class));
    }

    /** Result of a create: the stored entity and whether it was newly created. */
    public record CreateResult(Employee employee, boolean created) {}

    /**
     * Create. If the client did not supply an id, X-Request-Id (set by the gateway) is used as id,
     * which makes a gateway retry of the same POST idempotent (no duplicates).
     */
    public CreateResult create(Employee e, String requestId) throws DuplicateIdException {
        boolean idFromRequest = e.getId() == null || e.getId().isBlank();
        if (idFromRequest) e.setId(requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString());
        e.setUpdatedAt(Instant.now());
        try {
            mongo.insert(e);
            return new CreateResult(e, true);
        } catch (DuplicateKeyException ex) {
            if (idFromRequest) {
                // replay of the same request (retry) -> return what was already stored
                return new CreateResult(mongo.findById(e.getId(), Employee.class), false);
            }
            throw new DuplicateIdException(e.getId());
        }
    }

    /** Upsert (PUT semantic). Returns true when a new document was created. */
    public boolean replace(String id, Employee e) {
        e.setId(id);
        e.setUpdatedAt(Instant.now());
        boolean existed = mongo.exists(Query.query(Criteria.where("_id").is(id)), Employee.class);
        mongo.save(e);
        return !existed;
    }

    public boolean delete(String id) {
        return mongo.remove(Query.query(Criteria.where("_id").is(id)), Employee.class).getDeletedCount() > 0;
    }

    public static class DuplicateIdException extends Exception {
        public DuplicateIdException(String id) { super("Employee with id '" + id + "' already exists"); }
    }
}
