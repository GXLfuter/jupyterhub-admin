    -- 学生聊天数据库表
    -- 学生聊天消息表
    CREATE TABLE IF NOT EXISTS `chat_message` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
        `sender` VARCHAR(50) NOT NULL COMMENT '发送者用户名',
        `receiver` VARCHAR(50) DEFAULT NULL COMMENT '接收者用户名（NULL表示群聊）',
        `content` TEXT NOT NULL COMMENT '消息内容',
        `message_type` VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型：TEXT-普通文本，HAND_RAISE-举手消息',
        `attachments` TEXT COMMENT '附件信息（举手时的截图路径，JSON数组）',
        `is_group` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为群聊消息：0-私信，1-群聊',
        `is_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读，1-已读',
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
        `read_at` TIMESTAMP NULL COMMENT '阅读时间',
        INDEX `idx_sender` (`sender`),
        INDEX `idx_receiver` (`receiver`),
        INDEX `idx_created_at` (`created_at`),
        INDEX `idx_is_group` (`is_group`),
        INDEX `idx_is_read` (`is_read`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生聊天消息表';

    -- 学生聊天设置表
    CREATE TABLE IF NOT EXISTS `chat_settings` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '设置ID',
        `setting_key` VARCHAR(50) NOT NULL UNIQUE COMMENT '设置键',
        `setting_value` VARCHAR(255) NOT NULL COMMENT '设置值',
        `description` VARCHAR(255) DEFAULT NULL COMMENT '设置描述',
        `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天设置表';

    -- 初始化默认设置
    INSERT INTO `chat_settings` (`setting_key`, `setting_value`, `description`) VALUES
        ('group_chat_enabled', 'true', '大群聊天是否开启'),
        ('private_chat_enabled', 'true', '学生私信功能是否开启')
    ON DUPLICATE KEY UPDATE `setting_value` = VALUES(`setting_value`);

    -- 未读消息计数表
    CREATE TABLE IF NOT EXISTS `chat_unread_count` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
        `username` VARCHAR(50) NOT NULL COMMENT '用户名',
        `sender` VARCHAR(50) NOT NULL COMMENT '消息发送者',
        `unread_count` INT NOT NULL DEFAULT 0 COMMENT '未读消息数',
        `last_message_id` BIGINT DEFAULT NULL COMMENT '最后一条消息ID',
        `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
        UNIQUE KEY `uk_username_sender` (`username`, `sender`),
        INDEX `idx_username` (`username`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='未读消息计数表';
