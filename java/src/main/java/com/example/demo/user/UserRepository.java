package com.example.demo.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    public User saveIfPhoneAbsent(User user) {
        User existing = usersByPhone.putIfAbsent(user.phone(), user);
        return existing == null ? user : existing;
    }

    public void clear() {
        usersByPhone.clear();
    }
}
