package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.AuthRequest;
import com.fabriciojunio.codereview.dto.AuthResponse;
import com.fabriciojunio.codereview.dto.RegisterRequest;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.UserRepository;
import com.fabriciojunio.codereview.security.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Teste do cadastro e do login.
 *
 * Duas coisas são inegociáveis aqui e por isso viram asserção explícita:
 * senha nunca é gravada em texto puro, e login errado não emite token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtProvider jwtProvider;
    @Mock private AuthenticationManager authenticationManager;

    @InjectMocks private AuthService authService;

    private static final RegisterRequest CADASTRO =
            new RegisterRequest("Maria Silva", "maria@empresa.com", "senha-forte-123");

    private static final AuthRequest LOGIN =
            new AuthRequest("maria@empresa.com", "senha-forte-123");

    @Test
    @DisplayName("cadastra usuário novo e devolve token")
    void cadastra_usuario_novo() {
        when(userRepository.existsByEmail("maria@empresa.com")).thenReturn(false);
        when(passwordEncoder.encode("senha-forte-123")).thenReturn("$2a$10$hash");
        when(jwtProvider.generateToken("maria@empresa.com")).thenReturn("token-jwt");

        AuthResponse resposta = authService.register(CADASTRO);

        assertThat(resposta.token()).isEqualTo("token-jwt");
        assertThat(resposta.email()).isEqualTo("maria@empresa.com");
        assertThat(resposta.name()).isEqualTo("Maria Silva");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("nunca grava a senha em texto puro")
    void senha_vai_para_o_banco_com_hash() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode("senha-forte-123")).thenReturn("$2a$10$hash");
        when(jwtProvider.generateToken(anyString())).thenReturn("token-jwt");

        authService.register(CADASTRO);

        ArgumentCaptor<User> capturado = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(capturado.capture());

        assertThat(capturado.getValue().getPassword())
                .isEqualTo("$2a$10$hash")
                .isNotEqualTo("senha-forte-123");
    }

    @Test
    @DisplayName("recusa cadastro com e-mail já usado, e não salva nada")
    void recusa_email_repetido() {
        when(userRepository.existsByEmail("maria@empresa.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(CADASTRO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(jwtProvider);
    }

    @Test
    @DisplayName("faz login e devolve token do usuário certo")
    void login_bem_sucedido() {
        User usuario = User.builder()
                .email("maria@empresa.com").name("Maria Silva").password("$2a$10$hash")
                .build();

        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findByEmail("maria@empresa.com")).thenReturn(Optional.of(usuario));
        when(jwtProvider.generateToken("maria@empresa.com")).thenReturn("token-jwt");

        AuthResponse resposta = authService.login(LOGIN);

        assertThat(resposta.token()).isEqualTo("token-jwt");
        assertThat(resposta.name()).isEqualTo("Maria Silva");
    }

    @Test
    @DisplayName("senha errada não emite token")
    void senha_errada_nao_emite_token() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(LOGIN))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtProvider);
    }

    @Test
    @DisplayName("autentica antes de olhar o banco, e com as credenciais recebidas")
    void autentica_com_as_credenciais_recebidas() {
        User usuario = User.builder()
                .email("maria@empresa.com").name("Maria Silva").password("$2a$10$hash")
                .build();
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(usuario));
        when(jwtProvider.generateToken(anyString())).thenReturn("token-jwt");

        authService.login(LOGIN);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> capturado =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(capturado.capture());

        assertThat(capturado.getValue().getPrincipal()).isEqualTo("maria@empresa.com");
        assertThat(capturado.getValue().getCredentials()).isEqualTo("senha-forte-123");
    }

    @Test
    @DisplayName("usuário some do banco entre autenticar e buscar: falha, não emite token")
    void usuario_sumiu_depois_de_autenticar() {
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findByEmail("maria@empresa.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(LOGIN))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(jwtProvider);
    }
}
