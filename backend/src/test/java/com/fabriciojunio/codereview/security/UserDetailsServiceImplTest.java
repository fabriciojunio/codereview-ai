package com.fabriciojunio.codereview.security;

import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl")
class UserDetailsServiceImplTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private UserDetailsServiceImpl service;

    @Test
    @DisplayName("carrega o usuário com o hash e o papel USER")
    void carrega_usuario() {
        User usuario = User.builder()
                .email("maria@empresa.com").name("Maria").password("$2a$10$hash")
                .build();
        when(userRepository.findByEmail("maria@empresa.com")).thenReturn(Optional.of(usuario));

        UserDetails detalhes = service.loadUserByUsername("maria@empresa.com");

        assertThat(detalhes.getUsername()).isEqualTo("maria@empresa.com");
        assertThat(detalhes.getPassword()).isEqualTo("$2a$10$hash");
        assertThat(detalhes.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");
        assertThat(detalhes.isEnabled()).isTrue();
        assertThat(detalhes.isAccountNonExpired()).isTrue();
    }

    @Test
    @DisplayName("usuário inexistente vira UsernameNotFoundException, não nulo")
    void usuario_inexistente() {
        when(userRepository.findByEmail("ninguem@empresa.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ninguem@empresa.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ninguem@empresa.com");
    }
}
