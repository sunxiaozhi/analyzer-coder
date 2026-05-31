package com.example.demo.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final ConcurrentMap<String, User> usersByPhone = new ConcurrentHashMap<>();

    public List<User> findAll() {
        return new ArrayList<>(usersByPhone.values());
    }

    public Optional<User> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    public List<User> findByNameContaining(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return usersByPhone.values().stream()
                .filter(user -> user.name().toLowerCase(Locale.ROOT).contains(normalizedName))
                .toList();
    }

    public User saveIfPhoneAbsent(User user) {
        User existing = usersByPhone.putIfAbsent(user.phone(), user);
        return existing == null ? user : existing;
    }

    public Optional<User> updateByPhone(String phone, UnaryOperator<User> updater) {
        User updated = usersByPhone.computeIfPresent(phone, (key, existing) -> updater.apply(existing));
        return Optional.ofNullable(updated);
    }

    public boolean deleteByPhone(String phone) {
        return usersByPhone.remove(phone) != null;
    }

    public void clear() {
        usersByPhone.clear();
    }
}
