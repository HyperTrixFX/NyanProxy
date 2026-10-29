package moe.koseirin.nyanruaineo.services;

import moe.koseirin.nyanruaineo.utils.Qbot.QbotCommandCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小游戏必须不依赖 AI 也能玩；这里用 null AIServices 模拟 AI 关闭。
 */
class QbotGameServiceTest {

    private final QbotGameService gameService = new QbotGameService(null);

    @Test
    void localGamesWorkWithoutAi() {
        assertTrue(gameService.menu().contains("/guess"));

        String guessStart = gameService.startOrGuess("user-a", "");
        assertTrue(guessStart.contains("1~100"));
        String guessResult = gameService.startOrGuess("user-a", "50");
        assertNotNull(guessResult);
        assertFalse(guessResult.isBlank());

        String rps = gameService.rps("user-b", "石头");
        assertTrue(rps.contains("主人出石头"));

        String roll = gameService.roll("user-c", "2d6");
        assertTrue(roll.contains("2d6"));

        String quiz = gameService.startQuiz("user-d");
        assertTrue(quiz.contains("【问答】"));
        String answer = gameService.answerQuiz("user-d", "A");
        assertFalse(answer.isBlank());

        String fortune = gameService.fortune("user-e");
        assertTrue(fortune.contains("今日运势"));
    }

    @Test
    void storyFallsBackWhenAiUnavailable() {
        String story = gameService.story("user-f", "", "主人");
        assertTrue(story.contains("故事开始") || story.contains("接着写"));
        assertTrue(story.contains("AI 现在不在") || story.contains("手写剧情"));

        String continued = gameService.story("user-f", "推开门看看", "主人");
        assertFalse(continued.isBlank());
        assertTrue(gameService.giveUp("user-f").contains("故事"));
    }

    @Test
    void menuTextIsAvailable() {
        assertFalse(QbotCommandCatalog.HELP.isBlank());
        assertTrue(QbotCommandCatalog.GAME_MENU.contains("/fortune"));
        // 用户可见文本里不能出现 AI / 人工智能等非人身份词。
        assertFalse(QbotCommandCatalog.HELP.contains("AI"));
        assertFalse(QbotCommandCatalog.GAME_MENU.contains("AI"));
        assertFalse(QbotCommandCatalog.CHAT_UNAVAILABLE.contains("AI"));
    }
}
