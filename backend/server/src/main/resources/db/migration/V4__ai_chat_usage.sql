-- AI 对话：每账号每日发送条数（与解读 / 综合建议的额度分开计，控制费用）。对话内容不入库。
CREATE TABLE ai_chat_usage (
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    usage_day  VARCHAR(10) NOT NULL,
    messages   INT         NOT NULL,
    PRIMARY KEY (account_id, usage_day)
);
