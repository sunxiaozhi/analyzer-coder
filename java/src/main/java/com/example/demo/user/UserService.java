package com.example.demo.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository repository;
    private final Clock clock;

    @Autowired
    public UserService(UserRepository repository) {
        this(repository, Clock.systemUTC());
    }

    UserService(UserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public User register(UserRegistrationRequest request) {
        String phone = normalize(request.phone());
        User user = new User(UUID.randomUUID(), phone, request.name().trim(), Instant.now(clock));
        User stored = repository.saveIfPhoneAbsent(user);
        if (!stored.id().equals(user.id())) {
            throw new DuplicatePhoneException(phone);
        }
        return stored;
    }

    public List<User> listUsers() {
        return repository.findAll();
    }

    public List<User> searchUsersByName(String name) {
        return repository.findByNameContaining(name.trim());
    }

    public Optional<User> findByPhone(String phone) {
        return repository.findByPhone(normalize(phone));
    }

    public Optional<User> updateProfile(String phone, UserProfileUpdateRequest request) {
        String normalizedPhone = normalize(phone);
        String name = request.name().trim();
        return repository.updateByPhone(
                normalizedPhone,
                existing -> new User(existing.id(), existing.phone(), name, existing.createdAt())
        );
    }

    public boolean deleteByPhone(String phone) {
        return repository.deleteByPhone(normalize(phone));
    }

    private static String normalize(String phone) {
        return phone.trim();
    }
}
