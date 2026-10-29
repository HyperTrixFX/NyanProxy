package moe.koseirin.nyanruaineo.Minecraft.util;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RouteQuery {

    public static final AttributeKey<Map<String, String>> PARAMS =
            AttributeKey.valueOf("nyan.route.params");
    public static final AttributeKey<String> CLEAN_HOST =
            AttributeKey.valueOf("nyan.route.cleanHost");
    public static final AttributeKey<String> RAW_HOST =
            AttributeKey.valueOf("nyan.route.raw");

    private RouteQuery() {}

    public static String attach(Channel ch, String hostName, int port) {
        if (hostName == null) hostName = "";

        int nul = hostName.indexOf('\0');
        if (nul >= 0) hostName = hostName.substring(0, nul);

        ch.attr(RAW_HOST).set(hostName);

        String cleanHost;
        String query = null;
        int q = hostName.indexOf('?');
        if (q >= 0) {
            cleanHost = hostName.substring(0, q);
            query = hostName.substring(q + 1);
        } else {
            cleanHost = hostName;
        }

        ch.attr(CLEAN_HOST).set(cleanHost);
        ch.attr(PARAMS).set(parseQuery(query));
        return cleanHost;
    }

    public static Map<String, String> get(Channel ch) {
        Map<String, String> m = ch.attr(PARAMS).get();
        return m == null ? Collections.emptyMap() : m;
    }

    public static String get(Channel ch, String key) {
        return get(ch).get(key);
    }

    public static String getOrDefault(Channel ch, String key, String def) {
        String v = get(ch, key);
        return v == null ? def : v;
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        QueryStringDecoder decoder = new QueryStringDecoder("?" + query);
        Map<String, List<String>> raw = decoder.parameters();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            List<String> vs = e.getValue();
            map.put(e.getKey(), vs.isEmpty() ? "" : vs.getFirst());
        }
        return Collections.unmodifiableMap(map);
    }
}