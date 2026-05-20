-- V2: Tabela de métricas de análise para monitoramento de uso e performance
CREATE TABLE analysis_metrics (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id   UUID REFERENCES reviews(id) ON DELETE SET NULL,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    language    VARCHAR(50)  NOT NULL,
    model_used  VARCHAR(100) NOT NULL,
    tokens_in   INTEGER,
    tokens_out  INTEGER,
    duration_ms INTEGER,
    cache_hit   BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_metrics_user_id    ON analysis_metrics(user_id);
CREATE INDEX idx_metrics_language   ON analysis_metrics(language);
CREATE INDEX idx_metrics_created_at ON analysis_metrics(created_at DESC);
CREATE INDEX idx_metrics_cache_hit  ON analysis_metrics(cache_hit) WHERE cache_hit = false;
