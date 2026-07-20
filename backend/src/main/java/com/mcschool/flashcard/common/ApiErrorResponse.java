package com.mcschool.flashcard.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Error body returned by every failed API call. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        List<FieldValidationError> fieldErrors
) {

    public record FieldValidationError(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String errorCode, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, errorCode, message, path, null);
    }

    public static ApiErrorResponse validation(String message, String path,
                                              List<FieldValidationError> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), 400, "VALIDATION_FAILED", message, path, fieldErrors);
    }
}
