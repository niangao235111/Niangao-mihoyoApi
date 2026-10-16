package com.niangao.mihoyo.modules.workbench.mapper;

/** 数据访问接口：读取和续期原子执行；删除后的 Key 不得被更新操作复活。 */
public interface WorkbenchSessionMapper {
    record Stored(String encrypted, String csrf, long createdAt, long expiresAt, long absoluteExpiresAt) {
        @Override
        public String toString() { return "WorkbenchSession[redacted]"; }
    }
    Stored insert(String digest, String encrypted, String csrf);
    Stored read(String digest, boolean touch);
    void delete(String digest);
}
