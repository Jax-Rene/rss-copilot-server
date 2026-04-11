package com.rsscopilot.server.common;

import org.springframework.http.HttpStatus;

public class NotFoundException extends AppException {

  public NotFoundException(String message) {
    super("NOT_FOUND", HttpStatus.NOT_FOUND, message);
  }
}
