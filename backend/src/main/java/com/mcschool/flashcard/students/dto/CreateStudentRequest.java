package com.mcschool.flashcard.students.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStudentRequest(
        @NotBlank @Size(max = 100) String fullName,
        // Per the PRD this may be the student's or a parent's email address.
        @NotBlank @Email @Size(max = 255) String email
) {
}
