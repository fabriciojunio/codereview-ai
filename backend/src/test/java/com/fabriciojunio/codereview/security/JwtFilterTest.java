package com.fabriciojunio.codereview.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Teste do filtro que transforma token em identidade.
 *
 * A regra que sustenta a autenticação inteira é: só existe usuário no
 * contexto se o token for válido. Qualquer caminho que popule o
 * SecurityContext sem validar antes é falha de autenticação, então cada
 * caso ruim aqui confere que o contexto ficou vazio.
 *
 * O filtro também nunca pode interromper a cadeia: pedido sem token tem
 * que seguir e ser barrado depois, pelas regras de autorização.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtFilter")
class JwtFilterTest {

    @Mock private JwtProvider jwtProvider;
    @Mock private UserDetailsService userDetailsService;
    @Mock private FilterChain filterChain;

    @InjectMocks private JwtFilter filter;

    private final MockHttpServletRequest pedido = new MockHttpServletRequest();
    private final MockHttpServletResponse resposta = new MockHttpServletResponse();

    private static final UserDetails MARIA = User.builder()
            .username("maria@empresa.com").password("$2a$10$hash").roles("USER").build();

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private void executar() throws Exception {
        filter.doFilterInternal(pedido, resposta, filterChain);
    }

    private Object usuarioNoContexto() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }

    @Test
    @DisplayName("token válido autentica e segue a cadeia")
    void token_valido_autentica() throws Exception {
        pedido.addHeader("Authorization", "Bearer token-bom");
        when(jwtProvider.validateToken("token-bom")).thenReturn(true);
        when(jwtProvider.extractEmail("token-bom")).thenReturn("maria@empresa.com");
        when(userDetailsService.loadUserByUsername("maria@empresa.com")).thenReturn(MARIA);

        executar();

        assertThat(usuarioNoContexto()).isEqualTo(MARIA);
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("token inválido não autentica, mas o pedido segue")
    void token_invalido_nao_autentica() throws Exception {
        pedido.addHeader("Authorization", "Bearer token-forjado");
        when(jwtProvider.validateToken("token-forjado")).thenReturn(false);

        executar();

        assertThat(usuarioNoContexto()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("pedido sem cabeçalho Authorization segue sem autenticar")
    void sem_cabecalho() throws Exception {
        executar();

        assertThat(usuarioNoContexto()).isNull();
        verifyNoInteractions(jwtProvider);
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("esquema diferente de Bearer é ignorado")
    void esquema_errado() throws Exception {
        pedido.addHeader("Authorization", "Basic bWFyaWE6c2VuaGE=");

        executar();

        assertThat(usuarioNoContexto()).isNull();
        verifyNoInteractions(jwtProvider);
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("Bearer sem token não chega a validar nada")
    void bearer_vazio() throws Exception {
        pedido.addHeader("Authorization", "Bearer ");

        executar();

        assertThat(usuarioNoContexto()).isNull();
        verifyNoInteractions(jwtProvider);
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("cabeçalho vazio não autentica")
    void cabecalho_vazio() throws Exception {
        pedido.addHeader("Authorization", "");

        executar();

        assertThat(usuarioNoContexto()).isNull();
        verify(filterChain).doFilter(pedido, resposta);
    }

    @Test
    @DisplayName("token válido de usuário que sumiu do banco não vira sessão")
    void usuario_do_token_nao_existe_mais() {
        pedido.addHeader("Authorization", "Bearer token-de-usuario-removido");
        when(jwtProvider.validateToken(anyString())).thenReturn(true);
        when(jwtProvider.extractEmail(anyString())).thenReturn("removido@empresa.com");
        when(userDetailsService.loadUserByUsername("removido@empresa.com"))
                .thenThrow(new UsernameNotFoundException("User not found"));

        assertThatThrownBy(this::executar).isInstanceOf(UsernameNotFoundException.class);

        assertThat(usuarioNoContexto()).isNull();
    }

    @Test
    @DisplayName("autenticação carrega os papéis do usuário")
    void carrega_os_papeis() throws Exception {
        pedido.addHeader("Authorization", "Bearer token-bom");
        when(jwtProvider.validateToken(anyString())).thenReturn(true);
        when(jwtProvider.extractEmail(anyString())).thenReturn("maria@empresa.com");
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(MARIA);

        executar();

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");
    }
}
