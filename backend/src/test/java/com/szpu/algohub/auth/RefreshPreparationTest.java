package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.szpu.algohub.auth.identity.IdentityMapper;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.school.SchoolService;

class RefreshPreparationTest {
    @Test void profileReadFailureDoesNotConsumeBrowserRefreshToken() {
        var users = mock(AppUserMapper.class);
        var tokens = mock(TokenStore.class);
        var jwt = mock(JwtService.class);
        var identity = mock(IdentityMapper.class);
        var auth = spy(new AuthService(users, identity, mock(PasswordEncoder.class), mock(SchoolService.class),
                jwt, tokens, new AppProperties(), mock(AuthRateLimit.class),
                mock(com.szpu.algohub.admin.console.AdminUsers.class), mock(com.szpu.algohub.admin.console.AdminAccess.class),
                mock(com.szpu.algohub.school.CohortLeaderMapper.class)));
        var user = new AppUser();
        user.setId(9L); user.setUsername("test"); user.setStatus("ACTIVE");
        when(tokens.refreshIdentity("old-cookie", "browser")).thenReturn(new TokenStore.RefreshIdentity(9L, ""));
        when(users.findById(9L)).thenReturn(user);
        when(jwt.issueAccessToken(user)).thenReturn(new JwtService.IssuedToken("jwt", "jti", Instant.now(), Instant.now().plusSeconds(60)));
        when(jwt.accessTokenTtl()).thenReturn(Duration.ofMinutes(1));
        doThrow(new IllegalStateException("temporary profile read failure")).when(auth).me(9L);

        assertThatThrownBy(() -> auth.refresh("old-cookie", "test", "browser")).isInstanceOf(IllegalStateException.class);
        verify(tokens, never()).rotate(anyString(), anyString());
        verify(tokens, never()).revokeRefreshToken(anyString());
    }
}
