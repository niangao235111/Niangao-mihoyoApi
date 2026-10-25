-- 原神祈愿记录；先执行 010_create_genshin_archive.sql。本脚本不更改已有档案。
CREATE TABLE IF NOT EXISTS ng_genshin_wish_record (
    account_id BIGINT UNSIGNED NOT NULL,
    record_id DECIMAL(20,0) NOT NULL,
    gacha_type VARCHAR(4) NOT NULL,
    pool_group VARCHAR(4) NOT NULL,
    item_id VARCHAR(20) NOT NULL DEFAULT '',
    name VARCHAR(128) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    rank_type TINYINT UNSIGNED NOT NULL,
    quantity SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    wish_time DATETIME NOT NULL COMMENT '国服原始时间 UTC+8',
    source VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(account_id, record_id),
    KEY ix_wish_pool(account_id,pool_group,record_id),
    CONSTRAINT fk_wish_account FOREIGN KEY(account_id) REFERENCES ng_genshin_account(id),
    CONSTRAINT ck_wish_rank CHECK(rank_type IN (3,4,5)),
    CONSTRAINT ck_wish_pool CHECK(gacha_type IN ('100','200','301','302','400','500'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ng_genshin_wish_sync_run (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    cancel_requested TINYINT NOT NULL DEFAULT 0,
    fetched_count INT NOT NULL DEFAULT 0,
    inserted_count INT NOT NULL DEFAULT 0,
    duplicate_count INT NOT NULL DEFAULT 0,
    progress_json JSON NULL,
    error_stage VARCHAR(48) NULL,
    error_reason VARCHAR(48) NULL,
    upstream_retcode INT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    PRIMARY KEY(task_id),
    KEY ix_wish_task_account(account_id,created_at),
    CONSTRAINT fk_wish_run_account FOREIGN KEY(account_id) REFERENCES ng_genshin_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
