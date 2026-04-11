package com.rsscopilot.server.common;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

  private final String code;
  private final HttpStatus status;

  public AppException(String code, HttpStatus status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public AppException(String code, HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }
}
