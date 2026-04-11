package com.rsscopilot.server.common;

import org.springframework.http.HttpStatus;

public class BadRequestException extends AppException {

  public BadRequestException(String message) {
    super("BAD_REQUEST", HttpStatus.BAD_REQUEST, message);
  }
}
