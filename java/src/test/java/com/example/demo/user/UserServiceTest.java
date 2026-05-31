package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class UserServiceTest {
    private final UserRepository repository = new UserRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-28T00:00:00Z"), ZoneOffset.UTC);
    private final UserService service = new UserService(repository, clock);

    @Test
    void registersUser() {
        User user = service.register(new UserRegistrationRequest("13800138000", " Alice "));

        assertThat(user.phone()).isEqualTo("13800138000");
        assertThat(user.name()).isEqualTo("Alice");
        assertThat(user.createdAt()).isEqualTo(Instant.parse("2026-05-28T00:00:00Z"));
    }

    @Test
    void rejectsDuplicatePhone() {
        service.register(new UserRegistrationRequest("13800138000", "Alice"));

        assertThatThrownBy(() -> service.register(new UserRegistrationRequest("13800138000", "Bob")))
                .isInstanceOf(DuplicatePhoneException.class)
                .hasMessageContaining("13800138000");
    }

    @Test
    void searchesUpdatesAndDeletesUser() {
        service.register(new UserRegistrationRequest("13800138000", "Alice Zhang"));
        service.register(new UserRegistrationRequest("13900139000", "Bob Li"));

        assertThat(service.searchUsersByName("alice"))
                .extracting(User::phone)
                .containsExactly("13800138000");

        User updated = service.updateProfile(
                        "13800138000",
                        new UserProfileUpdateRequest(" Alice Chen ")
                )
                .orElseThrow();

        assertThat(updated.name()).isEqualTo("Alice Chen");
        assertThat(updated.createdAt()).isEqualTo(Instant.parse("2026-05-28T00:00:00Z"));
        assertThat(service.findByPhone("13800138000")).get().extracting(User::name).isEqualTo("Alice Chen");

        assertThat(service.deleteByPhone("13800138000")).isTrue();
        assertThat(service.findByPhone("13800138000")).isEmpty();
        assertThat(service.deleteByPhone("13800138000")).isFalse();
    }
}
