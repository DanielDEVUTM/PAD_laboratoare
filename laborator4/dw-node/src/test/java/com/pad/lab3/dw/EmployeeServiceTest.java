package com.pad.lab3.dw;

import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmployeeServiceTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final EmployeeService service = new EmployeeService(mongo);

    @Test
    void createWithoutIdUsesTheRequestIdOfTheGateway() throws Exception {
        EmployeeService.CreateResult r = service.create(new Employee(), "req-1");
        assertTrue(r.created());
        assertEquals("req-1", r.employee().getId());
        assertNotNull(r.employee().getUpdatedAt());
    }

    @Test
    void retriedCreateReturnsTheStoredDocumentInsteadOfADuplicate() throws Exception {
        Employee stored = new Employee();
        stored.setId("req-1");
        when(mongo.insert(any(Employee.class))).thenThrow(new DuplicateKeyException("dup"));
        when(mongo.findById("req-1", Employee.class)).thenReturn(stored);

        EmployeeService.CreateResult r = service.create(new Employee(), "req-1");

        assertFalse(r.created());
        assertSame(stored, r.employee());
    }

    @Test
    void duplicateIdChosenByTheClientIsAnError() {
        Employee e = new Employee();
        e.setId("1");
        when(mongo.insert(any(Employee.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThrows(EmployeeService.DuplicateIdException.class, () -> service.create(e, "req-2"));
    }

    @Test
    void replaceReportsWhetherTheDocumentIsNew() {
        when(mongo.exists(any(Query.class), eq(Employee.class))).thenReturn(false, true);
        assertTrue(service.replace("9", new Employee()));
        assertFalse(service.replace("9", new Employee()));
    }

    @Test
    void deleteReportsWhetherSomethingWasRemoved() {
        when(mongo.remove(any(Query.class), eq(Employee.class)))
                .thenReturn(DeleteResult.acknowledged(1), DeleteResult.acknowledged(0));
        assertTrue(service.delete("1"));
        assertFalse(service.delete("1"));
    }
}
