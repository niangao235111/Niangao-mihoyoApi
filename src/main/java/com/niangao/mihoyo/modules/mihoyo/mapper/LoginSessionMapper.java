package com.niangao.mihoyo.modules.mihoyo.mapper;

import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginOperation;

/** Redis 存储边界：读取的是快照，必须通过原子状态转换提交变更。 */
public interface LoginSessionMapper {
    /** 原子检查容量、保存会话并设置固定 TTL；返回 Redis 时间创建的快照。 */
    LoginSession insert(LoginSession session);

    /** 读取有效快照，同时把超过执行期限的遗留请求置为 FAILED。 */
    LoginSession findById(String id);

    /** 校验版本、状态、次数及冷却后，原子取得执行资格。 */
    LoginSession beginOperation(
            String id, long expectedVersion, LoginOperation operation, boolean hasProof);

    /** 仅当前操作 ID 且未超时才能保存结果；不能续期或复活已删除会话。 */
    LoginSession completeOperation(LoginSession session, String operationId);

    /** 上游失败时按操作 ID 标记失败，迟到的失败回调不能覆盖新状态。 */
    void failOperation(String id, String operationId);

    /** 删除会话及容量索引，不删除手机号冷却。 */
    void delete(String id);
}
