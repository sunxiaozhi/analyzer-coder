package com.example.demo.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserRegistrationRequest(
        @NotBlank
        @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "phone must contain 6 to 20 digits and may start with +")
        String phone,

        @NotBlank
        String name
) {
}
