package moe.koseirin.nyanruaineo;

/*
 * @author KoseiRin_
 * awa
 */

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import com.github.houbb.sensitive.word.support.check.WordChecks;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.github.houbb.sensitive.word.support.ignore.SensitiveWordCharIgnores;
import com.github.houbb.sensitive.word.support.resultcondition.WordResultConditions;
import com.github.houbb.sensitive.word.support.tag.WordTags;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.logging.Logger;


    @SpringBootApplication
    @EnableScheduling
    @EnableAsync
    @EnableJpaRepositories(basePackages = "moe.koseirin.nyanruaineo.repository")
    @EnableRedisRepositories(basePackages = "moe.koseirin.nyanruaineo.utils.RedisUtils")
//    @PropertySource(value = {
//            "classpath:Permissions.yml",
//    }, encoding = "utf-8")
    public class NyanIdApplication {
    public static Path configPath = Paths.get("config");
    public static Path DataPath = Paths.get("Data");
    public static Path UserAvatar = Paths.get("Data/UserAvatar");
    public static Path GIFAvatar = Paths.get("Data/GIFAvatar");
    public static Path UserDataPath = Paths.get("Data/UserData");
    public static Path YggdrasilTexture = Paths.get("Data/YggdrasilTexture");
    public static Path PluginsPath = Paths.get("plugins");
    public static SensitiveWordBs wordBs =SensitiveWordBs.newInstance()
            .wordAllow(WordAllows.defaults())
            .wordDeny(WordDenys.defaults())
            .wordResultCondition(WordResultConditions.englishWordNumMatch())
            .ignoreCase(true)
            .ignoreWidth(true)
            .ignoreNumStyle(true)
            .ignoreChineseStyle(true)
            .ignoreEnglishStyle(true)
            .ignoreRepeat(false)
            .enableNumCheck(false)
            .enableEmailCheck(false)
            .enableUrlCheck(false)
            .enableIpv4Check(false)
            .enableWordCheck(true)
            .wordFailFast(false)
            .wordCheckNum(WordChecks.num())
            .wordCheckEmail(WordChecks.email())
            .wordCheckUrl(WordChecks.url())
            .wordCheckIpv4(WordChecks.ipv4())
            .wordCheckWord(WordChecks.word())
            .numCheckLen(8)
            .wordTag(WordTags.none())
            .charIgnore(SensitiveWordCharIgnores.defaults())
            .wordResultCondition(WordResultConditions.alwaysTrue())
            .init();


    public static void main(String[] args) throws Exception {
        if (!Files.exists(configPath) || !Files.exists(DataPath) || !Files.exists(UserAvatar) || !Files.exists(UserDataPath) || !Files.exists(PluginsPath)|| !Files.exists(GIFAvatar)|| !Files.exists(YggdrasilTexture)) {
            Files.createDirectories(configPath);
            Files.createDirectories(DataPath);
            Files.createDirectories(UserAvatar);
            Files.createDirectories(UserDataPath);
            Files.createDirectories(PluginsPath);
            Files.createDirectories(GIFAvatar);
            Files.createDirectories(YggdrasilTexture);
        }else {
            Resource resource = new ClassPathResource("application.cfg");
            Resource PermissionsFile = new ClassPathResource("Permissions.yml");
            Path targetPath = configPath.resolve(Objects.requireNonNull(Objects.requireNonNull(resource.getFilename()).replace("cfg","yml")));
            Path targetPath1 = configPath.resolve(Objects.requireNonNull(PermissionsFile.getFilename()));
            if (!Files.exists(targetPath)) {
                Files.copy(resource.getInputStream(), targetPath);
                Logger.getLogger("NyanID").info("[NyanID-UserServer] ["+ LocalDateTime.now() +"] : 配置文件已复制到config文件夹,请修改配置文件再运行喵~");

            }
            if (!Files.exists(targetPath1)){
                Files.copy(PermissionsFile.getInputStream(),targetPath1);
                Logger.getLogger("NyanID").info("[NyanID-UserServer] ["+ LocalDateTime.now() +"] : Permissions文件已复制到config文件夹喵~");

            }
                wordBs.enableWordCheck(true);
                wordBs.enableIpv4Check(true);
                wordBs.enableEmailCheck(true);
                wordBs.enableUrlCheck(true);
                wordBs.init();
                SpringApplication.run(NyanIdApplication.class, args);

        }
    }

    }

