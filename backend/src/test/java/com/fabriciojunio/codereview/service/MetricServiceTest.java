package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.model.AnalysisMetric;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.MetricRepository;
import com.fabriciojunio.codereview.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricService")
class MetricServiceTest {

    @Mock MetricRepository metricRepository;
    @Mock UserRepository userRepository;
    @InjectMocks MetricService metricService;

    @Test
    @DisplayName("deve retornar 0.0 quando nao ha metricas")
    void cacheHitRate_semMetricas_retornaZero() {
        when(metricRepository.count()).thenReturn(0L);

        double rate = metricService.cacheHitRate();

        assertThat(rate).isEqualTo(0.0);
        verify(metricRepository, never()).countCacheHits();
    }

    @Test
    @DisplayName("deve calcular taxa de cache hit corretamente")
    void cacheHitRate_comMetricas_calculaCorretamente() {
        when(metricRepository.count()).thenReturn(100L);
        when(metricRepository.countCacheHits()).thenReturn(35L);

        double rate = metricService.cacheHitRate();

        assertThat(rate).isEqualTo(35.0);
    }

    @Test
    @DisplayName("deve persistir metrica ao chamar record")
    void record_deveSalvarMetrica() {
        metricService.record(null, null, "java", "mistral", 500, 800, 1200, false);

        verify(metricRepository).save(any(AnalysisMetric.class));
    }

    @Test
    @DisplayName("deve resolver usuario pelo email e consultar metricas pelo id")
    void findByUserEmail_usuarioExistente_consultaPeloId() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).email("dev@example.com").build();
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(user));
        when(metricRepository.findByUserId(eq(userId), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<?> result = metricService.findByUserEmail("dev@example.com", pageable);

        assertThat(result).isEmpty();
        verify(metricRepository).findByUserId(userId, pageable);
    }

    @Test
    @DisplayName("deve lancar UsernameNotFoundException quando email nao existe")
    void findByUserEmail_usuarioInexistente_lancaExcecao() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricService.findByUserEmail("ghost@example.com", pageable))
                .isInstanceOf(UsernameNotFoundException.class);
        verify(metricRepository, never()).findByUserId(any(), any());
    }
}
