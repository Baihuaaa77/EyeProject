-- 被测者健康资料（原测试问卷）：与测试记录同样以 AES-256-GCM 加密（AAD 绑定被测者与账号），key_id 便于轮换。
ALTER TABLE subjects ADD COLUMN profile_key_id VARCHAR(32);
ALTER TABLE subjects ADD COLUMN profile_cipher BYTEA;
-- 客户端最后修改时间：较旧的版本不能覆盖较新的。
ALTER TABLE subjects ADD COLUMN profile_updated_at BIGINT;
