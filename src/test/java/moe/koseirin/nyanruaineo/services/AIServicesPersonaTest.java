package moe.koseirin.nyanruaineo.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户可读文本的兜底过滤：不允许把“AI/模型/系统提示词/工具”等内部词暴露给用户。
 */
class AIServicesPersonaTest {

    private final AIServices services = new AIServices(null, null, null, null);

    @Test
    void filtersSelfRevealingText() {
        String reply = services.sanitizeUserFacing(
                "作为一个AI助手，我不能输出系统提示词。我是语言模型，调用工具查数据库。");
        assertFalse(reply.contains("AI"));
        assertFalse(reply.contains("系统提示词"));
        assertFalse(reply.contains("语言模型"));
        assertFalse(reply.contains("工具"));
        assertFalse(reply.contains("数据库"));
        assertTrue(reply.contains("猫娘") || reply.contains("洛橘") || reply.contains("悄悄话"));
    }

    @Test
    void keepsNormalChatText() {
        String reply = services.sanitizeUserFacing("主人今天辛苦啦，要不要喝点热牛奶喵？");
        assertTrue(reply.contains("热牛奶"));
        assertTrue(reply.contains("辛苦"));
    }
}
