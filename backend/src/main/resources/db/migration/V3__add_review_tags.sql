-- V3: Tags para categorizar revisões e facilitar filtragem
CREATE TABLE review_tags (
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    tag       VARCHAR(50) NOT NULL,
    PRIMARY KEY (review_id, tag)
);

CREATE INDEX idx_review_tags_tag ON review_tags(tag);

-- Popular tags padrão para as primeiras revisões
COMMENT ON TABLE review_tags IS 'Tags associadas a revisões para categorização e busca';
