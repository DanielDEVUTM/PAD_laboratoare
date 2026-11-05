package com.pad.lab3.dw;

import com.mongodb.MongoException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ErrorHandler {

    private static ResponseEntity<ApiError> build(HttpStatus s, String message) {
        return ResponseEntity.status(s).body(new ApiError(s.value(), s.getReasonPhrase(), message));
    }

    @ExceptionHandler(EmployeeController.NotFoundException.class)
    ResponseEntity<ApiError> notFound(EmployeeController.NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(EmployeeService.DuplicateIdException.class)
    ResponseEntity<ApiError> conflict(EmployeeService.DuplicateIdException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest(Exception e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    /** Database unavailable (e.g. no primary during an election) -> 503, the gateway retries elsewhere. */
    @ExceptionHandler({MongoException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ApiError> db(Exception e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable: " + e.getClass().getSimpleName());
    }
}
