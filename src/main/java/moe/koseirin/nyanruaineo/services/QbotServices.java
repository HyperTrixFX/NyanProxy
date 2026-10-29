package moe.koseirin.nyanruaineo.services;

import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.api.Intents;
import io.github.kloping.qqbot.api.v2.FriendMessageEvent;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.impl.ListenerHost;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.eventbus.EventBus;
import moe.koseirin.nyanruaineo.eventbus.SysEvent.QbotFriendMessageReceivedEvent;
import moe.koseirin.nyanruaineo.eventbus.SysEvent.QbotGroupMessageReceivedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
 * @author KoseiRin_
 * awa
 */
@Service
@Slf4j
public class QbotServices {

    @Value("${NyanidSetting.Qbot.isEnable}")
    private boolean IsEnable;

    @Value("${NyanidSetting.Qbot.appid}")
    private String appid;

    @Value("${NyanidSetting.Qbot.secret}")
    private String secret;

    private final EventBus eventBus;

    public QbotServices(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void initQbot() {
        if (IsEnable) {
            Starter starter = new Starter(appid, secret);
            starter.getConfig().setCode(Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS));
            starter.run();
            starter.registerListenerHost(new ListenerHost() {

                @EventReceiver
                public void onFriendMessage(FriendMessageEvent event) {
                    eventBus.postAsync(new QbotFriendMessageReceivedEvent(event));
                }

                @EventReceiver
                public void onGroupMessage(GroupMessageEvent event) {
                    eventBus.postAsync(new QbotGroupMessageReceivedEvent(event));
                }
            });
        } else {
            log.warn("Qbot已在配置文件中被关闭。");
        }
    }
}
