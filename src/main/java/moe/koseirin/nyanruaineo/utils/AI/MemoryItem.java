package moe.koseirin.nyanruaineo.utils.AI;

import java.util.List;

/**
 * 从一次会话中提取出的一条长期记忆。
 */
public record MemoryItem(
        String type,
        String content,
        List<String> keywords,
        int importance,
        String sourceType
) {
    public MemoryItem {
        if (keywords == null) {
            keywords = List.of();
        }
    }
}
