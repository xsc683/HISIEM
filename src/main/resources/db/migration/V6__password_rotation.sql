-- 安全基线：新导入/已有用户首次登录必须完成密码轮换。
ALTER TABLE users
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT TRUE;
