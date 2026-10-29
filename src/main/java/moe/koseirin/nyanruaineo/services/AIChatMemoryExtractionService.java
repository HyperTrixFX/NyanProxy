package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.entity.AIChatMemoryExtraction;
import moe.koseirin.nyanruaineo.repository.AIChatMemoryExtractionRepository;
import moe.koseirin.nyanruaineo.utils.AI.AIChatCacheKeys;
import moe.koseirin.nyanruaineo.utils.AI.ChatMessage;
import moe.koseirin.nyanruaineo.utils.AI.MemoryItem;
import moe.koseirin.nyanruaineo.utils.AI.SYSTEM_PROMPT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时会话到期前的长期记忆提取。
 * <p>
 * 原缓存架构不变：会话历史仍是 Redis List，TTL 5 小时。本任务只做两件事：
 * <ol>
 *     <li>扫描 {@code romyu:chat:history:*}，找出剩余 TTL ≤ 10 分钟的会话；</li>
 *     <li>读取该会话的归属 union_openid，把「上次提取之后新增」的消息交给 AI 整理成结构化记忆，
 *         写入 MySQL；AI 不可用/解析失败时保存原始片段兜底。</li>
 * </ol>
 * 提取进度记录在 {@code ai_chat_memory_extraction}，所以同一窗口不会重复提取；用户随后继续聊天时，
 * 只有新增消息会在下一次到期窗口被处理。
 */
@Slf4j
@Service
public class AIChatMemoryExtractionService {

    private static final int MAX_TRANSCRIPT_LENGTH = 8000;

    private final StringRedisTemplate redisTemplate;
    private final AIChatMemoryService memoryService;
    private final AIChatMemoryExtractionRepository extractionRepository;
    private final AIServices aiServices;
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    @Value("${ai.memory.enable:true}")
    private boolean memoryEnabled;
    @Value("${ai.memory.extract-before-expire-seconds:600}")
    private long extractBeforeSeconds;
    @Value("${ai.memory.max-sessions-per-scan:20}")
    private int maxSessionsPerScan;

    public AIChatMemoryExtractionService(StringRedisTemplate redisTemplate,
                                         AIChatMemoryService memoryService,
                                         AIChatMemoryExtractionRepository extractionRepository,
                                         AIServices aiServices) {
        this.redisTemplate = redisTemplate;
        this.memoryService = memoryService;
        this.extractionRepository = extractionRepository;
        this.aiServices = aiServices;
    }

    @Scheduled(
            fixedDelayString = "${ai.memory.scan-interval-ms:60000}",
            initialDelayString = "${ai.memory.initial-delay-ms:60000}"
    )
    public void scanExpiringSessions() {
        // ai.enable=false 表示所有 AI 服务关闭：不提取新记忆，已有记忆仍可通过 /memory 查看、/forget 删除。
        if (!memoryEnabled || !aiServices.isEnabled()) {
            return;
        }
        Set<String> keys = scanHistoryKeys();
        if (keys.isEmpty()) {
            return;
        }
        int processed = 0;
        for (String key : keys) {
            if (processed >= Math.max(1, maxSessionsPerScan)) {
                break;
            }
            Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0 || ttl > Math.max(1, extractBeforeSeconds)) {
                continue;
            }
            String sessionId = key.substring(AIChatCacheKeys.HISTORY_PREFIX.length());
            if (sessionId.isBlank() || !inProgress.add(sessionId)) {
                continue;
            }
            try {
                Map<String, String> meta = aiServices.getSessionMeta(sessionId);
                String ownerOpenid = meta.get(AIChatCacheKeys.META_OWNER_OPENID);
                if (ownerOpenid == null || ownerOpenid.isBlank()) {
                    // 不是 QQ 会话（或旧缓存没有归属元数据），不占用本轮处理名额。
                    continue;
                }
                if (processed >= Math.max(1, maxSessionsPerScan)) {
                    break;
                }
                if (extractSession(sessionId)) {
                    processed++;
                }
            } catch (Exception e) {
                log.error("Failed to extract memory for session {}", sessionId, e);
            } finally {
                inProgress.remove(sessionId);
            }
        }
    }

    /**
     * 立即处理一个会话（便于测试或手动补偿），同样遵守提取进度。
     *
     * @return 本次是否实际走到提取/更新进度（已处理过、无归属、无历史时返回 false）
     */
    public boolean extractSession(String sessionId) {
        if (!memoryEnabled || !aiServices.isEnabled()) {
            return false;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Map<String, String> meta = aiServices.getSessionMeta(sessionId);
        String ownerOpenid = meta.get(AIChatCacheKeys.META_OWNER_OPENID);
        if (ownerOpenid == null || ownerOpenid.isBlank()) {
            // 不是 QQ 会话（或旧缓存没有归属元数据），不写长期记忆。
            return false;
        }

        String historyKey = AIChatCacheKeys.historyKey(sessionId);
        List<String> jsonList = redisTemplate.opsForList().range(historyKey, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return false;
        }
        int totalMessages = jsonList.size();
        AIChatMemoryExtraction progress = extractionRepository.findById(sessionId).orElse(null);
        int extractedCount = progress == null ? 0 : Math.max(0, progress.getExtractedMessageCount());
        if (extractedCount >= totalMessages) {
            return false;
        }

        int from = Math.min(extractedCount, totalMessages);
        List<ChatMessage> newMessages = parseMessages(jsonList.subList(from, totalMessages));
        List<ChatMessage> conversation = newMessages.stream()
                .filter(message -> message != null
                        && ("user".equalsIgnoreCase(message.getRole()) || "assistant".equalsIgnoreCase(message.getRole())))
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .toList();
        if (conversation.isEmpty()) {
            saveProgress(progress, sessionId, ownerOpenid, totalMessages, "AI", null);
            return true;
        }

        String transcript = buildTranscript(conversation);
        if (transcript.isBlank()) {
            saveProgress(progress, sessionId, ownerOpenid, totalMessages, "AI", null);
            return true;
        }

        ExtractionResult aiResult = tryExtractWithAI(transcript);
        List<MemoryItem> items;
        String sourceType;
        String error = null;
        if (aiResult != null) {
            items = aiResult.items();
            sourceType = "AI";
        } else {
            items = fallbackItems(conversation);
            sourceType = "FALLBACK";
            error = "AI extraction unavailable or unparsable; raw snippet fallback used";
        }

        int saved = memoryService.saveExtracted(ownerOpenid, sessionId, items, sourceType);
        saveProgress(progress, sessionId, ownerOpenid, totalMessages, sourceType, error);
        log.info("Extracted {} long-term memory item(s) from session {} (owner {}, source {}, processed {} messages)",
                saved, sessionId, ownerOpenid, sourceType, totalMessages - extractedCount);
        return true;
    }

    private Set<String> scanHistoryKeys() {
        try {
            Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
                Set<String> result = new HashSet<>();
                try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions()
                        .match(AIChatCacheKeys.HISTORY_PREFIX + "*")
                        .count(200)
                        .build())) {
                    while (cursor.hasNext()) {
                        result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return result;
            });
            return keys == null ? Set.of() : keys;
        } catch (Exception e) {
            log.warn("Failed to scan AI chat history keys", e);
            return Set.of();
        }
    }

    private List<ChatMessage> parseMessages(List<String> jsonList) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String json : jsonList) {
            try {
                ChatMessage message = JSON.parseObject(json, ChatMessage.class);
                if (message != null) {
                    messages.add(message);
                }
            } catch (Exception e) {
                log.debug("Skipped malformed chat history entry during memory extraction", e);
            }
        }
        return messages;
    }

    private String buildTranscript(List<ChatMessage> conversation) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : conversation) {
            String content = stripLegacyEnvelope(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(message.getRole())) {
                sb.append("用户：").append(content).append('\n');
            } else {
                sb.append("洛橘：").append(content).append('\n');
            }
        }
        String transcript = sb.toString().trim();
        if (transcript.length() > MAX_TRANSCRIPT_LENGTH) {
            transcript = transcript.substring(transcript.length() - MAX_TRANSCRIPT_LENGTH);
            int firstNewline = transcript.indexOf('\n');
            if (firstNewline >= 0 && firstNewline < 200) {
                transcript = transcript.substring(firstNewline + 1);
            }
        }
        return transcript;
    }

    private String stripLegacyEnvelope(String content) {
        if (content == null) {
            return "";
        }
        String value = content
                .replace("<<<USER_MESSAGE>>>", "")
                .replace("<<<END_USER_MESSAGE>>>", "")
                .trim();
        // 兼容旧版本把“当前用户信息：...”拼进 user 消息的历史数据。
        value = value.replaceAll("(?m)^当前用户信息：.*(?:\\R|$)", "");
        value = value.replaceAll("(?m)^【当前对话环境】.*(?:\\R|$)", "");
        return value.trim();
    }

    private ExtractionResult tryExtractWithAI(String transcript) {
        if (!aiServices.isEnabled()) {
            return null;
        }
        String response = aiServices.generateText(SYSTEM_PROMPT.memoryExtractor.getPROMPT(), transcript);
        if (response == null || response.isBlank()) {
            return null;
        }
        String json = stripCodeFence(response);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JSONObject root = JSON.parseObject(json.substring(start, end + 1));
            JSONArray array = root.getJSONArray("memories");
            if (array == null) {
                return null;
            }
            List<MemoryItem> items = new ArrayList<>();
            for (int i = 0; i < array.size() && items.size() < 8; i++) {
                JSONObject node = array.getJSONObject(i);
                if (node == null) {
                    continue;
                }
                String content = node.getString("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                String type = node.getString("type");
                List<String> keywords = parseKeywords(node.get("keywords"));
                int importance = node.getIntValue("importance", 2);
                items.add(new MemoryItem(type, content, keywords, importance, "AI"));
            }
            return new ExtractionResult(items);
        } catch (Exception e) {
            log.debug("Failed to parse memory extraction JSON", e);
            return null;
        }
    }

    private List<String> parseKeywords(Object value) {
        List<String> keywords = new ArrayList<>();
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                if (item != null && !item.toString().isBlank()) {
                    keywords.add(item.toString());
                }
            }
        } else if (value instanceof String string) {
            for (String part : string.split("[,，、\\s]+")) {
                if (!part.isBlank()) {
                    keywords.add(part);
                }
            }
        }
        return keywords;
    }

    /**
     * AI 不可用/解析失败时的兜底：保存最近几条用户原话与洛橘回应，至少不丢记忆。
     */
    private List<MemoryItem> fallbackItems(List<ChatMessage> conversation) {
        List<String> userLines = new ArrayList<>();
        String lastAssistant = "";
        for (ChatMessage message : conversation) {
            String content = stripLegacyEnvelope(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(message.getRole())) {
                userLines.add(content);
            } else {
                lastAssistant = content;
            }
        }
        if (userLines.isEmpty()) {
            return List.of();
        }
        List<String> recent = userLines.subList(Math.max(0, userLines.size() - 3), userLines.size());
        String userText = String.join("；", recent);
        if (userText.length() > 450) {
            userText = userText.substring(userText.length() - 450);
        }
        StringBuilder content = new StringBuilder("一次会话中，用户提到：").append(userText);
        if (!lastAssistant.isBlank()) {
            content.append("；洛橘回应：").append(truncate(lastAssistant, 120));
        }
        return List.of(new MemoryItem(
                moe.koseirin.nyanruaineo.entity.AIChatMemory.TYPE_EVENT,
                content.toString(),
                List.of(),
                2,
                "FALLBACK"
        ));
    }

    private void saveProgress(AIChatMemoryExtraction existing, String sessionId, String ownerOpenid,
                              int totalMessages, String sourceType, String error) {
        AIChatMemoryExtraction progress = existing == null ? new AIChatMemoryExtraction() : existing;
        progress.setSessionId(sessionId);
        progress.setOwnerOpenid(ownerOpenid);
        progress.setExtractedMessageCount(totalMessages);
        progress.setExtractedAt(LocalDateTime.now());
        progress.setLastSourceType(sourceType);
        progress.setLastError(error == null ? null : truncate(error, 255));
        extractionRepository.save(progress);
    }

    private String stripCodeFence(String response) {
        String value = response.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            if (firstNewline >= 0) {
                value = value.substring(firstNewline + 1);
            }
            if (value.endsWith("```")) {
                value = value.substring(0, value.length() - 3);
            }
        }
        return value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max - 1);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private record ExtractionResult(List<MemoryItem> items) {
    }
}
