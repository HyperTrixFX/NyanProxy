package moe.koseirin.nyanruaineo.services;

import moe.koseirin.nyanruaineo.utils.AI.AIChatCacheKeys;
import moe.koseirin.nyanruaineo.utils.AI.ChatMessage;
import moe.koseirin.nyanruaineo.repository.AIChatMemoryExtractionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 `ai.enable=false` 时所有 AI 服务关闭：到期会话不会被提取成长期记忆。
 * <p>
 * 已有记忆仍可由 /memory 查看、/forget 删除；这里只验证“不会产生新记忆”。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "NyanidSetting.EnableProxy=false",
                "NyanidSetting.Qbot.isEnable=false",
                "ai.enable=false",
                "ai.memory.enable=true",
                "ai.memory.scan-interval-ms=3600000",
                "ai.memory.initial-delay-ms=3600000"
        }
)
class AIChatMemoryExtractionIntegrationTest {

    @Autowired
    private AIChatMemoryExtractionService extractionService;
    @Autowired
    private AIChatMemoryService memoryService;
    @Autowired
    private AIChatMemoryExtractionRepository extractionRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void doesNotExtractWhenAiIsDisabled() {
        String owner = "test-union-" + UUID.randomUUID();
        String historyKey = AIChatCacheKeys.historyKey(owner);
        String metaKey = AIChatCacheKeys.metaKey(owner);
        try {
            ChatMessage user = new ChatMessage("user", "咱喜欢在深夜玩生存服，也喜欢热牛奶。");
            ChatMessage assistant = new ChatMessage("assistant", "记住啦喵，深夜的生存服和热牛奶。");
            redisTemplate.opsForList().rightPushAll(historyKey,
                    com.alibaba.fastjson2.JSON.toJSONString(user),
                    com.alibaba.fastjson2.JSON.toJSONString(assistant));
            redisTemplate.expire(historyKey, Duration.ofSeconds(500));
            redisTemplate.opsForHash().putAll(metaKey, Map.of(
                    AIChatCacheKeys.META_OWNER_OPENID, owner,
                    AIChatCacheKeys.META_PLATFORM, "QQ_PRIVATE",
                    AIChatCacheKeys.META_DISPLAY_NAME, "测试主人"
            ));
            redisTemplate.expire(metaKey, Duration.ofSeconds(500));

            @SuppressWarnings("unchecked")
            Set<String> scanned = (Set<String>) ReflectionTestUtils.invokeMethod(extractionService, "scanHistoryKeys");
            assertTrue(scanned != null && scanned.contains(historyKey), "Redis SCAN 应该能发现到期会话");

            extractionService.scanExpiringSessions();
            extractionService.extractSession(owner);

            assertEquals(0, memoryService.count(owner), "AI 关闭时不应写入新记忆");
            assertFalse(extractionRepository.findById(owner).isPresent(), "AI 关闭时不应记录提取进度");
        } finally {
            memoryService.deleteAll(owner);
            extractionRepository.deleteBySessionId(owner);
            redisTemplate.delete(List.of(historyKey, metaKey));
        }
    }
}
