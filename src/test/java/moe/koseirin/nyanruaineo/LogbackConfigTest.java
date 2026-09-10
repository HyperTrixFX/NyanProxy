package moe.koseirin.nyanruaineo;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.AppenderAttachable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code logback-spring.xml} 的冒烟测试：这个文件会接管 Spring Boot 的默认日志初始化，
 * 一旦里面的 resource include 路径写错或元素名拼错，应用启动时才会炸——而那时通常已经在跑生产了。
 * 这里用独立的 {@link LoggerContext}（不碰全局 logger）把它真正解析一遍。
 * <p>
 * 之所以要异步：默认的 ConsoleAppender 是同步的，代理端大量日志产生在 Netty 事件循环线程上，
 * 写 stdout 的系统调用会直接阻塞事件循环，把该线程上所有玩家的流量一起拖慢。
 */
class LogbackConfigTest {

    /** 从当前工作目录向上找 {@code src/main/resources/logback-spring.xml}。 */
    private static File locateConfig() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "src/main/resources/logback-spring.xml");
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    @Test
    void configParsesAndRoutesTheRootLoggerThroughAnAsyncConsoleAppender() throws Exception {
        File config = locateConfig();
        if (config == null) {
            fail("src/main/resources/logback-spring.xml not found");
            return;
        }

        LoggerContext context = new LoggerContext();
        context.setName("logback-config-test");
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);

            ch.qos.logback.classic.Logger root =
                    context.getLogger(Logger.ROOT_LOGGER_NAME);

            AsyncAppender async = null;
            for (Appender<?> appender : appendersOf(root)) {
                if (appender instanceof AsyncAppender candidate) {
                    async = candidate;
                }
            }
            assertNotNull(async, "the root logger must write through an AsyncAppender; a synchronous "
                    + "console appender lets log writes block the Netty event loop");

            // 队列与「绝不阻塞」策略：事件循环的实时性优先于日志完整性。
            assertTrue(async.getQueueSize() >= 1024,
                    "queue must be large enough to absorb a burst, was " + async.getQueueSize());
            assertTrue(async.isNeverBlock(),
                    "neverBlock must be on: blocking is exactly what we are trying to avoid");

            // 异步 appender 必须真的挂着一个下游 appender，否则日志会被丢掉。
            List<Appender<?>> targets = new ArrayList<>();
            if (async instanceof AppenderAttachable<?> attachable) {
                attachable.iteratorForAppenders().forEachRemaining(targets::add);
            }
            assertTrue(targets.stream().anyMatch(a -> "CONSOLE".equals(a.getName())),
                    "the async appender must wrap the Spring Boot CONSOLE appender, targets=" + targets);

            // 真正打一条：解析成功但 pattern 有问题的话，这里会炸或输出空白。
            context.getLogger("logback-config-test").info("logback-spring.xml smoke test");
        } finally {
            context.stop();
        }
    }

    private static List<Appender<?>> appendersOf(ch.qos.logback.classic.Logger logger) {
        List<Appender<?>> appenders = new ArrayList<>();
        logger.iteratorForAppenders().forEachRemaining(appenders::add);
        return appenders;
    }
}
