# User Registration Rules

Users must provide a unique phone number during registration.

The service should check whether the phone number already exists before creating a user.
The database should also keep a unique index as the final consistency guard.

## Duplicate Phone Handling

When a duplicate phone number is detected, return a clear validation error instead of a generic persistence exception.
