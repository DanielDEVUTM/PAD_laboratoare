package com.pad.lab3.dw;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;

/**
 * Maps HTTP requests to service operations. Resource-oriented URLs, no verbs in the path.
 *
 *   GET    /employees?offset=&limit=&id=&department=&updatedSince=   list (page)
 *   GET    /employees/{id}                                           read
 *   POST   /employees                                                create
 *   PUT    /employees/{id}                                           create-or-replace
 *   DELETE /employees/{id}                                           delete
 *
 * JSON and XML are both accepted (Content-Type) and produced (Accept or ?format=json|xml).
 */
@RestController
@RequestMapping(value = "/employees",
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    public EmployeePage list(@RequestParam(required = false) String id,
                             @RequestParam(required = false) String department,
                             @RequestParam(required = false) Instant updatedSince,
                             @RequestParam(defaultValue = "0") int offset,
                             @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return service.list(id, department, updatedSince, offset, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Employee> get(@PathVariable String id) {
        return service.find(id).map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException(id));
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<Employee> create(@Valid @RequestBody Employee body,
                                           @RequestHeader(value = "X-Request-Id", required = false) String requestId)
            throws EmployeeService.DuplicateIdException {
        EmployeeService.CreateResult r = service.create(body, requestId);
        HttpStatus status = r.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .location(URI.create("/employees/" + r.employee().getId()))
                .body(r.employee());
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<Employee> put(@PathVariable String id, @Valid @RequestBody Employee body) {
        boolean created = service.replace(id, body);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!service.delete(id)) throw new NotFoundException(id);
        return ResponseEntity.noContent().build();
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String id) { super("Employee '" + id + "' not found"); }
    }
}
