package com.rsscopilot.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerUnitTest {

  @Test
  void shouldLogUnexpectedExceptionsForServerSideDiagnosis(CapturedOutput output) {
    ApiExceptionHandler handler = new ApiExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/entries");
    IllegalStateException exception = new IllegalStateException("database is locked");

    var response = handler.handleUnexpectedException(exception, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(response.getBody().message()).isEqualTo("unexpected server error on /api/entries");
    assertThat(output).contains("unexpected server error on /api/entries");
    assertThat(output).contains("database is locked");
  }
}
