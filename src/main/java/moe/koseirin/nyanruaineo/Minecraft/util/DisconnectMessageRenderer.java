package moe.koseirin.nyanruaineo.Minecraft.util;

/*
 * @author KoseiRin_
 * awa
 */

import java.util.Map;

/**
 * 踢出/封禁屏幕模板的统一渲染器。
 * <p>
 * 规则：{@code n}（或 {@code |}）换行、{@code &} → {@code §} 颜色码、占位符替换
 * （占位符值可为 null，替换为空串）。踢出与封禁两个模板共用本方法，保证渲染行为一致。
 */
public final class DisconnectMessageRenderer {

    private DisconnectMessageRenderer() {
    }

    /** 把模板渲染成带 {@code §} 颜色码的多行文本。 */
    public static String render(String template, Map<String, String> placeholders) {
        if (template == null || template.isBlank()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (String raw : template.split("[\n|]")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
        }

        String text = out.toString().replace('&', '\u00A7');
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                text = text.replace(entry.getKey(), value);
            }
        }
        return text;
    }
}
