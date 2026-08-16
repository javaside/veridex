package io.veridex.trace.api;

import io.veridex.trace.application.TraceBodyReadService.TraceBodyReadFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TraceBodyController.class)
public class TraceBodyExceptionHandler {
    @ExceptionHandler(TraceBodyReadFailure.class)
    ResponseEntity<Void> handleDecryptFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
