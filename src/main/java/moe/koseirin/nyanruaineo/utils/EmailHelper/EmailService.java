package moe.koseirin.nyanruaineo.utils.EmailHelper;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.utils.System.EnumList.EmailBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class EmailService {


    private final JavaMailSender javaMailSender;

    public EmailService(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
    }


    @Value("${spring.mail.username}")
    private String from;

    @Value("${NyanidSetting.ContactEmail}")
    private String contact;

    @Value("${NyanidSetting.TeamName}")
    private String teamName;


    private final Map<String, String> emailTexts = Map.of(
            "email.title", "NyanID[Nyanruaineo Stuido]",
            "greeting.prefix", "亲爱的猫猫",
            "contact.us", "联系我们",
            "do.not.reply", "请勿回复此系统邮件",
            "email.subject", "NyanID[Nyanruaineo Stuido]"
    );


    private String loadEmailTemplate() throws Exception {
        ClassPathResource resource = new ClassPathResource("email.html");
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

    private String replaceVariables(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }


    public void sendmail(String mail,String user,String rpl) {
        try {
            // 1. 加载HTML模板
            String htmlTemplate = loadEmailTemplate();

            // 3. 构建替换参数
            Map<String, String> variables = new HashMap<>();
            variables.put("emailTitle", emailTexts.get("email.title"));
            variables.put("greetingPrefix", emailTexts.get("greeting.prefix"));
            variables.put("user", user);
            variables.put("bodyContent", rpl);
            variables.put("teamName", teamName);
            variables.put("contactUs", emailTexts.get("contact.us"));
            variables.put("contact", contact);
            variables.put("doNotReply", emailTexts.get("do.not.reply"));

            // 4. 替换模板变量
            String htmlBody = replaceVariables(htmlTemplate, variables);


            // 5. 准备邮件
            MimeMessagePreparator preparator = (MimeMessage mimeMessage) -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setTo(mail);
                helper.setSubject(emailTexts.get("email.subject"));
                helper.setText(htmlBody,true);
                helper.setFrom(from);
            };

            // 6. 发送邮件
            javaMailSender.send(preparator);

        } catch (Exception e) {
            // 添加日志记录和错误处理

        }
    }

    public void sendCodeMail(String email, String uid, String code) {
        try {
            // 1. 加载HTML模板
            String htmlTemplate = loadEmailTemplate();

            // 3. 构建替换参数
            Map<String, String> variables = new HashMap<>();
            variables.put("emailTitle", emailTexts.get("email.title"));
            variables.put("greetingPrefix", emailTexts.get("greeting.prefix"));
            variables.put("user", uid);
            variables.put("greetingSuffix", emailTexts.get("greeting.suffix"));
            variables.put("bodyContent", emailTexts.get("body.content"));
            variables.put("body",EmailBody.VerificationCodeBody.getBody().replace("${code}", code) );
            variables.put("teamName", teamName);
            variables.put("contactUs", emailTexts.get("contact.us"));
            variables.put("contact", contact);
            variables.put("doNotReply", emailTexts.get("do.not.reply"));
            // 4. 替换模板变量
            String htmlBody = replaceVariables(htmlTemplate, variables);

            // 5. 准备邮件
            MimeMessagePreparator preparator = (MimeMessage mimeMessage) -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setTo(email);
                helper.setSubject(emailTexts.get("email.subject"));
                helper.setText(htmlBody,true);
                helper.setFrom(from);
            };

            // 6. 发送邮件
            javaMailSender.send(preparator);

        } catch (Exception e) {
            // 添加日志记录和错误处理

        }
    }




    /**
     * 发送 QQ 与 NyanID 绑定验证码。
     *
     * @return 邮件是否成功交给 JavaMailSender；失败时由调用方回滚验证码
     */
    public boolean sendQqBindCode(String email, String code) {
        try {
            String htmlBody = """
                    <!DOCTYPE html>
                    <html lang="zh-CN">
                    <head><meta charset="utf-8"></head>
                    <body style="margin:0;padding:24px;background:#f6f7fb;font-family:'Microsoft YaHei',sans-serif;color:#333;">
                      <div style="max-width:560px;margin:0 auto;background:#fff;border-radius:12px;padding:28px 32px;box-shadow:0 4px 18px rgba(0,0,0,.06);">
                        <h2 style="margin:0 0 16px;color:#1183cf;">NyanID [猫猫通行证平台]</h2>
                        <p>你好喵～有人正在把 QQ 与 NyanID 账号绑定。</p>
                        <p>本次绑定的验证码是：</p>
                        <p style="font-size:32px;font-weight:700;letter-spacing:8px;color:#ff8c00;margin:18px 0;">%s</p>
                        <p>验证码 <strong>5 分钟</strong>内有效，且只能使用一次。请回到与机器人的<strong>私聊</strong>中发送：
                          <code>/bind %s</code>
                        </p>
                        <p>如果这不是你本人操作，请忽略这封邮件，并检查你的 NyanID 账号安全。</p>
                        <hr style="border:none;border-top:1px solid #eee;margin:24px 0;">
                        <p style="font-size:12px;color:#999;">%s · 联系我们：%s<br>请勿回复此系统邮件</p>
                      </div>
                    </body>
                    </html>
                    """.formatted(code, code, teamName, contact);

            MimeMessagePreparator preparator = (MimeMessage mimeMessage) -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setTo(email);
                helper.setSubject("NyanID QQ 绑定验证码[猫猫通行证]");
                helper.setText(htmlBody, true);
                helper.setFrom(from);
            };
            javaMailSender.send(preparator);
            return true;
        } catch (Exception e) {
            log.error("Failed to send QQ binding code to {}", email, e);
            return false;
        }
    }

    public void sendVerificationCode(String user, String code) {
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            String htmlBody = "<!DOCTYPE html>\n" +
                    "<head>\n" +
                    "    <meta charset=\"utf-8\">\n" +
                    "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                    "    <meta name=\"description\" content=\"email code\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <table cellpadding=\"0\" align=\"center\"\n" +
                    "           style=\"width: 800px;height: 100%; margin: 0px auto; text-align: left; position: relative; border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 5px; border-bottom-left-radius: 5px; font-size: 14px; font-family:微软雅黑, 黑体; line-height: 1.5; box-shadow: rgb(153, 153, 153) 0px 0px 5px; border-collapse: collapse; background-position: initial initial; background-repeat: initial initial;background:#fff;\">\n" +
                    "        <tbody>\n" +
                    "        <tr>\n" +
                    "            <th valign=\"middle\"\n" +
                    "                style=\"height: 25px; line-height: 25px; padding: 15px 35px; border-bottom-width: 1px; border-bottom-style: solid; border-bottom-color: rgb(29, 216, 226); background-color: rgb(17, 131, 207); border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 0px; border-bottom-left-radius: 0px;\">\n" +
                    "                <font face=\"微软雅黑\" size=\"5\" style=\"color: rgb(255, 255, 255); \">NyanID [猫猫通行证平台]</font>\n" +
                    "            </th>\n" +
                    "        </tr>\n" +
                    "        <tr>\n" +
                    "            <td style=\"word-break:break-all\">\n" +
                    "                <div style=\"padding:25px 35px 40px; background-color:#fff;opacity:0.8;\">\n" +
                    "                    <h2 style=\"margin: 5px 0px; \">\n" +
                    "                        <font color=\"#333333\" style=\"line-height: 20px; \">\n" +
                    "                            <font style=\"line-height: 22px; \" size=\"4\">\n" +
                    "                                亲爱的猫猫"+user +
                    "                        </font>\n" +
                    "                    </h2>\n" +
                    "                    <p>您好喵,非常感谢您使用NyanID,我们注意到你的账户正在进行一些敏感的操作喵~所以我们需要验证你的邮箱以确保是您喵本人在操作,关注小鳥遊ホシノ喵!!!谢谢喵~\n" +
                    "                        verification code is:<font color=\"#ff8c00\">"+ code+"</font>, 杂鱼喵,这个验证码有效期为5分钟哦,请尽快使用喵!~</p>\n" +
                    "                    <div style=\"width:100%;margin:0 auto;\">\n" +
                    "                        <div style=\"padding:10px 10px 0;border-top:1px solid #ccc;color:#747474;margin-bottom:20px;line-height:1.3em;font-size:12px;\">\n" +
                    "                            <p>"+teamName+"</p>\n" +
                    "                            <p>Contact us： "+contact+"</p>\n" +
                    "                            <p>Please do not reply to this system email</p>\n" +
                    "                        </div>\n" +
                    "                    </div>\n" +
                    "                </div>\n" +
                    "            </td>\n" +
                    "        </tr>\n" +
                    "        </tbody>\n" +
                    "    </table>\n" +
                    "</body>\n" +
                    "</html>";
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage, true);
            messageHelper.setTo(user);
            messageHelper.setSubject("NyanID User verified[猫猫通行证]");
            messageHelper.setText(htmlBody, true);
             messageHelper.setFrom(from);
        };
        javaMailSender.send(messagePreparator);
    }



    public void RegisterVerification(String user, String link) {
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            String htmlBody = "<!DOCTYPE html>\n" +
                    "<head>\n" +
                    "    <meta charset=\"utf-8\">\n" +
                    "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                    "    <meta name=\"description\" content=\"email code\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <table cellpadding=\"0\" align=\"center\"\n" +
                    "           style=\"width: 800px;height: 100%; margin: 0px auto; text-align: left; position: relative; border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 5px; border-bottom-left-radius: 5px; font-size: 14px; font-family:微软雅黑, 黑体; line-height: 1.5; box-shadow: rgb(153, 153, 153) 0px 0px 5px; border-collapse: collapse; background-position: initial initial; background-repeat: initial initial;background:#fff;\">\n" +
                    "        <tbody>\n" +
                    "        <tr>\n" +
                    "            <th valign=\"middle\"\n" +
                    "                style=\"height: 25px; line-height: 25px; padding: 15px 35px; border-bottom-width: 1px; border-bottom-style: solid; border-bottom-color: rgb(29, 216, 226); background-color: rgb(17, 131, 207); border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 0px; border-bottom-left-radius: 0px;\">\n" +
                    "                <font face=\"微软雅黑\" size=\"5\" style=\"color: rgb(255, 255, 255); \">NyanID [猫猫通行证平台]</font>\n" +
                    "            </th>\n" +
                    "        </tr>\n" +
                    "        <tr>\n" +
                    "            <td style=\"word-break:break-all\">\n" +
                    "                <div style=\"padding:25px 35px 40px; background-color:#fff;opacity:0.8;\">\n" +
                    "                    <h2 style=\"margin: 5px 0px; \">\n" +
                    "                        <font color=\"#333333\" style=\"line-height: 20px; \">\n" +
                    "                            <font style=\"line-height: 22px; \" size=\"4\">\n" +
                    "                                亲爱的猫猫"+user +
                    "                        </font>\n" +
                    "                    </h2>\n" +
                    "                    <p>您好喵,非常感谢您注册使用NyanID,点击链接确认注册:)<a href=\""+link+"\">确认注册</a>\n" +
                    "                    <div style=\"width:100%;margin:0 auto;\">\n" +
                    "                        <div style=\"padding:10px 10px 0;border-top:1px solid #ccc;color:#747474;margin-bottom:20px;line-height:1.3em;font-size:12px;\">\n" +
                    "                            <p>"+teamName+"</p>\n" +
                    "                            <p>Contact us： "+contact+"<p>\n" +
                    "                            <p>Please do not reply to this system email</p>\n" +
                    "                        </div>\n" +
                    "                    </div>\n" +
                    "                </div>\n" +
                    "            </td>\n" +
                    "        </tr>\n" +
                    "        </tbody>\n" +
                    "    </table>\n" +
                    "</body>\n" +
                    "</html>";
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage, true);
            messageHelper.setTo(user);
            messageHelper.setSubject("NyanID User Register Verified[猫猫通行证]");
            messageHelper.setText(htmlBody, true);
            messageHelper.setFrom(from);
        };
        javaMailSender.send(messagePreparator);
    }
    //Notification
    public void NotificationEmail(String user, String ip,String Event,String uid) {
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            String htmlBody = "<!DOCTYPE html>\n" +
                    "<head>\n" +
                    "    <meta charset=\"utf-8\">\n" +
                    "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                    "    <meta name=\"description\" content=\"email code\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <table cellpadding=\"0\" align=\"center\"\n" +
                    "           style=\"width: 800px;height: 100%; margin: 0px auto; text-align: left; position: relative; border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 5px; border-bottom-left-radius: 5px; font-size: 14px; font-family:微软雅黑, 黑体; line-height: 1.5; box-shadow: rgb(153, 153, 153) 0px 0px 5px; border-collapse: collapse; background-position: initial initial; background-repeat: initial initial;background:#fff;\">\n" +
                    "        <tbody>\n" +
                    "        <tr>\n" +
                    "            <th valign=\"middle\"\n" +
                    "                style=\"height: 25px; line-height: 25px; padding: 15px 35px; border-bottom-width: 1px; border-bottom-style: solid; border-bottom-color: rgb(29, 216, 226); background-color: rgb(17, 131, 207); border-top-left-radius: 5px; border-top-right-radius: 5px; border-bottom-right-radius: 0px; border-bottom-left-radius: 0px;\">\n" +
                    "                <font face=\"微软雅黑\" size=\"5\" style=\"color: rgb(255, 255, 255); \">NyanID [猫猫通行证平台]</font>\n" +
                    "            </th>\n" +
                    "        </tr>\n" +
                    "        <tr>\n" +
                    "            <td style=\"word-break:break-all\">\n" +
                    "                <div style=\"padding:25px 35px 40px; background-color:#fff;opacity:0.8;\">\n" +
                    "                    <h2 style=\"margin: 5px 0px; \">\n" +
                    "                        <font color=\"#333333\" style=\"line-height: 20px; \">\n" +
                    "                            <font style=\"line-height: 22px; \" size=\"4\">\n" +
                    "                                亲爱的猫猫"+user +
                    "                        </font>\n" +
                    "                    </h2>\n" +
                    "                    <p>您好喵,非常感谢您使用NyanID,我们注意到你的账户正在进行一些敏感的操作,这封邮件是确认是否是你本人所操作喵~</p>\n" +
                    "                       <p><font color=\"#ff0000\">当你看见这封邮件时代表敏感事件已成功执行喵!!!!!!!</font></p>"+
                    "                       <p>操作类型: <font color=\"#ff0000\">"+Event+"</font></p>"+
                    "                       <p>请求的IP地址: <font color=\"#ff0000\">"+ip+"</font></p>"+
                    "                       <p>受影响的账户UID: <font color=\"#ff0000\">"+uid+"</font></p>"+
                    "                       <p><font color=\"#ff0000\">如果非你本人所操作请发送邮件至我们的邮箱进行账号恢复喵!</font></p>"+
                    "                    <div style=\"width:100%;margin:0 auto;\">\n" +
                    "                        <div style=\"padding:10px 10px 0;border-top:1px solid #ccc;color:#747474;margin-bottom:20px;line-height:1.3em;font-size:12px;\">\n" +
                    "                            <p>"+teamName+"</p>\n" +
                    "                            <p>Contact us： "+contact+"<p>\n" +
                    "                            <p>Please do not reply to this system email</p>\n" +
                    "                        </div>\n" +
                    "                    </div>\n" +
                    "                </div>\n" +
                    "            </td>\n" +
                    "        </tr>\n" +
                    "        </tbody>\n" +
                    "    </table>\n" +
                    "</body>\n" +
                    "</html>";
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage, true);
            messageHelper.setTo(user);
            messageHelper.setSubject("NyanID User Notification[猫猫通行证]");
            messageHelper.setText(htmlBody, true);
            messageHelper.setFrom(from);
        };
        javaMailSender.send(messagePreparator);
    }
}