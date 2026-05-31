package com.example.demo.user;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<User> register(@Valid @RequestBody UserRegistrationRequest request) {
        User user = service.register(request);
        return ResponseEntity.created(URI.create("/api/users/" + user.phone())).body(user);
    }

    @GetMapping
    public List<User> listUsers(@RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            return service.searchUsersByName(name);
        }
        return service.listUsers();
    }

    @GetMapping("/{phone}")
    public ResponseEntity<User> findByPhone(@PathVariable String phone) {
        return service.findByPhone(phone)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{phone}")
    public ResponseEntity<User> updateProfile(
            @PathVariable String phone,
            @Valid @RequestBody UserProfileUpdateRequest request
    ) {
        return service.updateProfile(phone, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{phone}")
    public ResponseEntity<Void> deleteByPhone(@PathVariable String phone) {
        if (service.deleteByPhone(phone)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
