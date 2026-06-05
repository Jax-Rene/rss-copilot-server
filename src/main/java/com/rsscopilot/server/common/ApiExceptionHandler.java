package com.rsscopilot.server.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiErrorResponse> handleAppException(AppException exception) {
    return ResponseEntity.status(exception.status())
        .body(new ApiErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiErrorResponse> handleValidationException(Exception exception) {
    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse("BAD_REQUEST", validationMessage(exception), Instant.now()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableRequestBody() {
    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse("BAD_REQUEST", "request body is invalid", Instant.now()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
      MethodArgumentTypeMismatchException exception) {
    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse("BAD_REQUEST", typeMismatchMessage(exception), Instant.now()));
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<ApiErrorResponse> handleNotFound(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            new ApiErrorResponse(
                "NOT_FOUND", "api endpoint not found: " + request.getRequestURI(), Instant.now()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
      Exception exception, HttpServletRequest request) {
    LOGGER.error("unexpected server error on {}", request.getRequestURI(), exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "unexpected server error on " + request.getRequestURI(),
                Instant.now()));
  }

  private String validationMessage(Exception exception) {
    BindingResult bindingResult = bindingResult(exception);
    if (bindingResult == null) {
      return "request validation failed";
    }

    return bindingResult.getFieldErrors().stream()
        .findFirst()
        .map(this::fieldErrorMessage)
        .orElseGet(
            () ->
                bindingResult.getGlobalErrors().stream()
                    .findFirst()
                    .map(this::objectErrorMessage)
                    .orElse("request validation failed"));
  }

  private BindingResult bindingResult(Exception exception) {
    if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
      return methodArgumentNotValidException.getBindingResult();
    }
    if (exception instanceof BindException bindException) {
      return bindException.getBindingResult();
    }
    return null;
  }

  private String typeMismatchMessage(MethodArgumentTypeMismatchException exception) {
    String name = exception.getName();
    if (!StringUtils.hasText(name)) {
      return "invalid request parameter";
    }
    return "invalid request parameter: " + name;
  }

  private String fieldErrorMessage(FieldError error) {
    String message = objectErrorMessage(error);
    return error.getField() + " " + message;
  }

  private String objectErrorMessage(ObjectError error) {
    return StringUtils.hasText(error.getDefaultMessage())
        ? error.getDefaultMessage()
        : "request validation failed";
  }
}
