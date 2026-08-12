package com.analyzercoder.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.CaptchaService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthControllerCookieTest {
    @Test
    void productionCookieIsSecureHttpOnlyAndSameSiteLax() {
        AuthController controller =
                new AuthController(mock(AuthService.class), mock(CaptchaService.class), true, 12);

        String header = controller.sessionCookie("token", Duration.ofHours(12)).toString();

        assertThat(header)
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=43200");
    }

    @Test
    void localDevelopmentCanDisableSecureCookie() {
        AuthController controller =
                new AuthController(mock(AuthService.class), mock(CaptchaService.class), false, 12);

        assertThat(controller.sessionCookie("token", Duration.ofHours(12)).toString())
                .doesNotContain("Secure");
    }
}
