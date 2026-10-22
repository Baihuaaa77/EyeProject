-- AI 解读：输出缓存（键为「提示词版本 + 模型 + 知识库版本 + 输入」的哈希，不关联账号）与每账号每日调用次数（控制费用）。
CREATE TABLE ai_explanations (
    cache_key   VARCHAR(64) PRIMARY KEY,
    output_json TEXT        NOT NULL,
    created_at  BIGINT      NOT NULL
);
CREATE INDEX idx_ai_explanations_created ON ai_explanations (created_at);

CREATE TABLE ai_usage (
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    usage_day  VARCHAR(10) NOT NULL,
    calls      INT         NOT NULL,
    PRIMARY KEY (account_id, usage_day)
);
