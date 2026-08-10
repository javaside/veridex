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

INSERT INTO users (username, password_hash, display_name, role) VALUES
    ('admin', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '平台管理员', 'PLATFORM_ADMIN'),
    ('kadmin', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '知识管理员', 'KNOWLEDGE_ADMIN'),
    ('employee', '$2a$10$smS4bF34/T/Nz8hCJHr6lOgQAoN/MqagALav7m02UHHSu9vJpfGyW', '员工', 'EMPLOYEE');
