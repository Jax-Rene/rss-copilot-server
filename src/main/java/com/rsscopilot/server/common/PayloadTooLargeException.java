package com.rsscopilot.server.common;

import org.springframework.http.HttpStatus;

public class PayloadTooLargeException extends AppException {

  public PayloadTooLargeException(String message) {
    super("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, message);
  }
}
