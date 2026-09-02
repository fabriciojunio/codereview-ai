package com.fabriciojunio.codereview.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aceita token emitido por um provedor de identidade externo.
 *
 * <p>O login próprio desta aplicação existe para ela rodar sozinha, e continua
 * sendo o padrão. Mas dentro de uma empresa a autenticação quase nunca é da
 * aplicação: ela vem do Keycloak, do Entra ID ou do Okta que o time de
 * identidade já opera, e uma ferramenta interna que pede cadastro próprio é
 * uma ferramenta que ninguém adota, além de virar mais um lugar com senha
 * guardada.
 *
 * <p>Fica desligado até alguém apontar o emissor em
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Ligado, o
 * Spring baixa as chaves públicas do provedor e valida assinatura, emissor e
 * validade sozinho. A aplicação nunca vê senha e não guarda chave de
 * assinatura.
 *
 * <p>É o mesmo desenho dos outros pontos de troca deste projeto: uma coisa a
 * mais que se liga por configuração, sem caminho paralelo no código.
 */
@Configuration
@ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
public class IdentidadeExterna {

    /**
     * Onde o Keycloak coloca os papéis do domínio.
     *
     * <p>Não existe padrão para isto. O OpenID Connect define claims de
     * identidade, não de autorização, então cada provedor inventou o próprio
     * lugar. Ler dos dois formatos mais comuns cobre a maioria das instalações
     * sem exigir configuração.
     */
    private static final String CLAIM_KEYCLOAK = "realm_access";
    private static final String CLAIM_PAPEIS = "roles";

    /** Prefixo que o Spring Security espera em papel, por convenção. */
    private static final String PREFIXO_PAPEL = "ROLE_";

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> conversorDeToken() {
        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(IdentidadeExterna::autoridadesDe);

        // O identificador do usuário sai do "sub", e não do e-mail ou do nome
        // de login: os dois mudam, o "sub" não. Auditoria presa a e-mail
        // aponta para a pessoa errada depois de um casamento ou um divórcio.
        conversor.setPrincipalClaimName("sub");
        return conversor;
    }

    /**
     * Junta os escopos do OAuth2 com os papéis do provedor.
     *
     * <p>São duas coisas diferentes que costumam ser confundidas. Escopo é o
     * que a aplicação cliente pode pedir; papel é o que a pessoa pode fazer.
     * Um token pode ter escopo de leitura e papel de administrador ao mesmo
     * tempo, e as duas informações importam.
     */
    private static Collection<GrantedAuthority> autoridadesDe(Jwt token) {
        Set<GrantedAuthority> autoridades = new HashSet<>(
                new JwtGrantedAuthoritiesConverter().convert(token));

        papeisDe(token).forEach(papel ->
                autoridades.add(new SimpleGrantedAuthority(comPrefixo(papel))));

        return autoridades;
    }

    @SuppressWarnings("unchecked")
    private static List<String> papeisDe(Jwt token) {
        // Formato do Keycloak: {"realm_access": {"roles": [...]}}
        Object realm = token.getClaim(CLAIM_KEYCLOAK);
        if (realm instanceof Map<?, ?> mapa && mapa.get(CLAIM_PAPEIS) instanceof List<?> lista) {
            return (List<String>) lista;
        }

        // Formato simples, usado por vários outros: {"roles": [...]}
        Object direto = token.getClaim(CLAIM_PAPEIS);
        if (direto instanceof List<?> lista) {
            return (List<String>) lista;
        }

        return List.of();
    }

    /**
     * Não duplica o prefixo quando o provedor já manda o papel com ele.
     *
     * <p>Sem esta conferência, um provedor que emite {@code ROLE_ADMIN} vira
     * {@code ROLE_ROLE_ADMIN} e a autorização falha em silêncio: o token é
     * válido, o usuário está autenticado, e o acesso é negado sem explicação.
     */
    private static String comPrefixo(String papel) {
        return papel.startsWith(PREFIXO_PAPEL) ? papel : PREFIXO_PAPEL + papel;
    }
}
