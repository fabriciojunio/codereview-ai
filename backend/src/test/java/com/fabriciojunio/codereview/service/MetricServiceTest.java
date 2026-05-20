package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.model.AnalysisMetric;
import com.fabriciojunio.codereview.repository.MetricRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricService")
class MetricServiceTest {

    @Mock MetricRepository metricRepository;
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
}
