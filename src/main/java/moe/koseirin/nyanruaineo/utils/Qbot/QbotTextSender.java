package moe.koseirin.nyanruaineo.utils.Qbot;

import io.github.kloping.qqbot.api.Sender;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ 文本消息发送工具：按长度拆分长回复，避免超过被动消息限制。
 */
@Slf4j
public final class QbotTextSender {

    private static final int MAX_CHUNK_LENGTH = 1500;
    private static final int MAX_CHUNKS = 3;
    private static final String BREAK_CHARS = "\n。！？；，、 ";

    private QbotTextSender() {
    }

    public static void send(Sender sender, String text) {
        if (sender == null || text == null || text.isBlank()) {
            return;
        }
        List<String> chunks = split(text.trim());
        for (String chunk : chunks) {
            if (chunk.isBlank()) {
                continue;
            }
            try {
                sender.send(chunk);
            } catch (Exception e) {
                log.error("Failed to send Qbot message chunk", e);
                break;
            }
        }
    }

    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= MAX_CHUNK_LENGTH) {
            chunks.add(text);
            return chunks;
        }
        int start = 0;
        while (start < text.length() && chunks.size() < MAX_CHUNKS) {
            int hardEnd = Math.min(start + MAX_CHUNK_LENGTH, text.length());
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int minBreak = start + MAX_CHUNK_LENGTH / 2;
                for (int i = hardEnd - 1; i >= minBreak; i--) {
                    if (BREAK_CHARS.indexOf(text.charAt(i)) >= 0) {
                        end = i + 1;
                        break;
                    }
                }
            }
            if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            if (end <= start) {
                end = hardEnd;
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        if (start < text.length()) {
            String last = chunks.get(chunks.size() - 1);
            chunks.set(chunks.size() - 1, last + "…（消息太长，咱先说到这里喵）");
        }
        return chunks;
    }
}
