CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    role VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 固定 UUID：测试与业务代码可稳定引用（admin=...001, kadmin=...002, employee=...003）
INSERT INTO users (id, username, password_hash, display_name, role) VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '平台管理员', 'PLATFORM_ADMIN'),
    ('00000000-0000-0000-0000-000000000002', 'kadmin', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '知识管理员', 'KNOWLEDGE_ADMIN'),
    ('00000000-0000-0000-0000-000000000003', 'employee', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '员工', 'EMPLOYEE');
