-- 玩家档案：仅保留最新成功数据，不保存每日快照。MySQL 8.0.16+。
-- 在 DataGrip 手工执行；此脚本不创建数据库、不修改现有 gacha_* / image_* 表。
-- IF NOT EXISTS 仅用于初次建表，不会升级已经存在的同名表。
-- DATETIME(3) 由应用统一读写 UTC。
-- 不保存 SToken、LToken、Cookie、mid、验证码或上游原始响应。
USE `genshinImpact`;
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS `ng_genshin_account` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `owner_stuid` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证米哈游账号ID；非游戏UID，非登录凭证',
    `game_uid` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `game_biz` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'hk4e_cn' COMMENT '国服统一标识，旧B服别名写入前归一化',
    `region` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'cn_gf01官服 / cn_qd01 B服',
    `nickname` VARCHAR(128) NULL,
    `adventure_level` SMALLINT UNSIGNED NULL,
    `avatar_url` VARCHAR(2048) NULL,
    `roster_synced_at` DATETIME(3) NULL COMMENT '角色列表最近成功读取时间，NULL表示未读取',
    `roster_sync_id` BIGINT UNSIGNED NULL COMMENT '列表更新代次，用于阻止旧响应覆盖新列表',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ng_account_owner_role` (`owner_stuid`, `region`, `game_uid`),
    CONSTRAINT `ck_ng_account_domestic` CHECK (`game_biz` = 'hk4e_cn' AND `region` IN ('cn_gf01', 'cn_qd01'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='米哈游账号归属与国服游戏角色';

CREATE TABLE IF NOT EXISTS `ng_genshin_sync_run` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '服务端分配的读取代次，非登录会话ID',
    `account_id` BIGINT UNSIGNED NOT NULL,
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING',
    `overview_succeeded` TINYINT UNSIGNED NOT NULL DEFAULT 0,
    `roster_succeeded` TINYINT UNSIGNED NOT NULL DEFAULT 0,
    `expected_detail_count` INT UNSIGNED NULL COMMENT '本次计划读取详情的角色数；NULL表示未确定',
    `succeeded_detail_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `failed_detail_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `error_stage` VARCHAR(64) NULL,
    `upstream_retcode` INT NULL,
    `error_reason` VARCHAR(128) NULL COMMENT '本站固定错误分类；不得写入原始上游message/响应',
    `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_at` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_ng_sync_account_time` (`account_id`, `started_at`),
    CONSTRAINT `fk_ng_sync_account` FOREIGN KEY (`account_id`) REFERENCES `ng_genshin_account` (`id`),
    CONSTRAINT `ck_ng_sync_status` CHECK (`status` IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT `ck_ng_sync_flags` CHECK (`overview_succeeded` IN (0, 1) AND `roster_succeeded` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='档案读取批次与部分成功记录';

CREATE TABLE IF NOT EXISTS `ng_genshin_overview` (
    `account_id` BIGINT UNSIGNED NOT NULL,
    `achievements` INT UNSIGNED NULL,
    `active_days` INT UNSIGNED NULL,
    `spiral_abyss` VARCHAR(32) NULL,
    `theater_max_act` SMALLINT UNSIGNED NULL,
    `theater_unlocked` TINYINT UNSIGNED NULL,
    `theater_has_data` TINYINT UNSIGNED NULL,
    `overview_json` JSON NOT NULL COMMENT '本站GenshinArchive.Overview白名单DTO，含extra/explorations等',
    `synced_at` DATETIME(3) NOT NULL COMMENT '成功读取时间；不因失败更新',
    `source_sync_id` BIGINT UNSIGNED NOT NULL COMMENT '读取代次，写入时比较大小；逻辑关联sync_run',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`account_id`),
    CONSTRAINT `fk_ng_overview_account` FOREIGN KEY (`account_id`) REFERENCES `ng_genshin_account` (`id`),
    CONSTRAINT `ck_ng_overview_flags` CHECK ((`theater_unlocked` IS NULL OR `theater_unlocked` IN (0, 1))
        AND (`theater_has_data` IS NULL OR `theater_has_data` IN (0, 1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号最新成功总览，未知数值保持NULL';

CREATE TABLE IF NOT EXISTS `ng_genshin_character` (
    `account_id` BIGINT UNSIGNED NOT NULL,
    `character_id` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '米哈游角色ID，非gacha_items内部自增ID',
    `name` VARCHAR(128) NULL,
    `element` VARCHAR(16) NULL,
    `rarity` TINYINT UNSIGNED NULL,
    `level` SMALLINT UNSIGNED NULL,
    `constellation` TINYINT UNSIGNED NULL,
    `friendship` SMALLINT UNSIGNED NULL,
    `in_roster` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最近一次成功列表中是否存在；刷新列表需事务更新',
    `basic_json` JSON NOT NULL COMMENT '角色列表白名单DTO；不能用它覆盖detail_json',
    `detail_json` JSON NULL COMMENT '成功详情白名单DTO，含技能/武器/圣遗物/属性',
    `basic_synced_at` DATETIME(3) NOT NULL,
    `detail_synced_at` DATETIME(3) NULL COMMENT 'NULL表示尚未读取详情；可能与基础信息不同时间',
    `basic_sync_id` BIGINT UNSIGNED NOT NULL,
    `detail_sync_id` BIGINT UNSIGNED NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`account_id`, `character_id`),
    KEY `idx_ng_character_roster` (`account_id`, `in_roster`, `level`),
    CONSTRAINT `fk_ng_character_account` FOREIGN KEY (`account_id`) REFERENCES `ng_genshin_account` (`id`),
    CONSTRAINT `ck_ng_character_roster` CHECK (`in_roster` IN (0, 1)),
    CONSTRAINT `ck_ng_character_detail` CHECK (
        (`detail_json` IS NULL AND `detail_synced_at` IS NULL AND `detail_sync_id` IS NULL)
        OR (`detail_json` IS NOT NULL AND `detail_synced_at` IS NOT NULL AND `detail_sync_id` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每个玩家角色的最新基础信息及独立装备详情';

-- 执行后核对新增表名；不会读取玩家数据。
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN (
    'ng_genshin_account', 'ng_genshin_sync_run', 'ng_genshin_overview',
    'ng_genshin_character')
ORDER BY table_name;
