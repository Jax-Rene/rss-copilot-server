package com.rsscopilot.server.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiErrorResponse> handleAppException(AppException exception) {
    return ResponseEntity.status(exception.status())
        .body(new ApiErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiErrorResponse> handleValidationException(Exception exception) {
    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse("BAD_REQUEST", "request validation failed", Instant.now()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
      Exception exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "unexpected server error on " + request.getRequestURI(),
                Instant.now()));
  }
}
