package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engineering-projects")
public class EngineeringProjectController {
    private final EngineeringProjectService service;

    public EngineeringProjectController(EngineeringProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<EngineeringProjectService.EngineeringProject> list(HttpServletRequest request) {
        return service.list(SecurityContext.account(request));
    }

    @GetMapping("/{id}")
    public EngineeringProjectService.EngineeringProject get(
            @PathVariable UUID id, HttpServletRequest request) {
        return service.get(SecurityContext.account(request), id);
    }

    @PostMapping
    public EngineeringProjectService.EngineeringProject create(
            @RequestBody EngineeringProjectService.ProjectInput body,
            HttpServletRequest request) {
        return service.create(
                SecurityContext.account(request), body, request.getRemoteAddr());
    }

    @PutMapping("/{id}")
    public EngineeringProjectService.EngineeringProject update(
            @PathVariable UUID id,
            @RequestBody EngineeringProjectService.ProjectInput body,
            HttpServletRequest request) {
        return service.update(
                SecurityContext.account(request), id, body, request.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable UUID id,
            @RequestParam long expectedVersion,
            HttpServletRequest request) {
        service.delete(
                SecurityContext.account(request), id, expectedVersion, request.getRemoteAddr());
    }
}
