package io.authweave.core.assessment.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.authweave.core.assessment.application.PersonalWorkspaceService;

@RestController
public class PersonalWorkspaceController {

    private final PersonalWorkspaceService service;

    public PersonalWorkspaceController(PersonalWorkspaceService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/personal-workspaces")
    public ResponseEntity<PersonalWorkspaceResponse> provision(
            @Valid @RequestBody PersonalWorkspaceRequest request) {
        if (!validIssuer(request.issuer())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OIDC issuer");
        }
        UUID workspaceId = service.provision(request.issuer(), request.subject());
        return ResponseEntity.ok(new PersonalWorkspaceResponse(workspaceId));
    }

    private static boolean validIssuer(String value) {
        try {
            URI issuer = URI.create(value);
            if (issuer.getHost() == null || issuer.getUserInfo() != null || issuer.getQuery() != null
                    || issuer.getFragment() != null || !issuer.toString().equals(value)) {
                return false;
            }
            return "https".equals(issuer.getScheme())
                    || "http://localhost:8081".equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public record PersonalWorkspaceRequest(
            @NotBlank @Size(max = 2048) String issuer,
            @NotBlank @Size(max = 256) String subject) {
    }

    public record PersonalWorkspaceResponse(UUID workspaceId) {
    }
}
