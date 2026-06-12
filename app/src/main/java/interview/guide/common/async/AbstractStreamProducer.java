package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 发送任务主流程
     * @param payload
     */
    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                streamKey(),
                buildMessage(payload),
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    /**
     * 截断错误信息
     * @param error
     * @return
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 任务名称
     * @return
     */
    protected abstract String taskDisplayName();

    /**
     * Stream Key
     * @return
     */
    protected abstract String streamKey();

    /**
     * 构建消息
     * @param payload
     * @return
     */
    protected abstract Map<String, String> buildMessage(T payload);

    /**
     * 任务负载标识
     * @param payload
     * @return
     */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 任务发送失败处理
     * @param payload 任务负载
     * @param error   错误信息
     */
    protected abstract void onSendFailed(T payload, String error);
}
