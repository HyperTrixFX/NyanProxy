package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.entity.TexturesList;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilPlayerRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.ErrorUtils.ErrorResponse;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.SqlService.TexturesListService;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * Yggdrasil textures（皮肤 / 披风上传）业务逻辑。
 */
@Service
public class YggdrasilTextureService {

    @Value("${yggdrasil.privateKey}")
    private String privateKey;

    private final YggdrasilPlayerRepository yggdrasilPlayerRepository;
    private final YggdrasilRepository yggdrasilRepository;
    private final UserDevicesRepository userDevicesRepository;
    private final BanUserRepository banUserRepository;
    private final utilset utilset;
    private final TexturesListService texturesListService;
    private final Respond respond;

    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52
    };

    private static final byte[] PNG_CMIM = {
            (byte) 0x00, 0x00
    };

    private static final byte[] PNG_ColorType = {
            (byte) 0x06
    };

    public YggdrasilTextureService(YggdrasilPlayerRepository yggdrasilPlayerRepository, YggdrasilRepository yggdrasilRepository, UserDevicesRepository userDevicesRepository, BanUserRepository banUserRepository, utilset utilset, TexturesListService texturesListService, Respond respond) {
        this.yggdrasilPlayerRepository = yggdrasilPlayerRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.userDevicesRepository = userDevicesRepository;
        this.banUserRepository = banUserRepository;
        this.utilset = utilset;
        this.texturesListService = texturesListService;
        this.respond = respond;
    }

    public ResponseEntity<?> putSkin(MultipartFile skin, String model, HttpServletRequest request) throws Exception {
        String Authorization = request.getHeader("Authorization");
        String raw = Authorization.replace("Bearer ", "").replace(" ", "");
        String Token = utilset.decrypt(raw, privateKey);
        String uid = userDevicesRepository.findUidByToken(Token);
        if (uid == null || banUserRepository.existsByUidAndIsActiveTrue(uid)) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("账户状态异常，资料为只读，无法修改", "ForbiddenOperationException", "ForbiddenOperationException"));
        }
        if (yggdrasilRepository.GetPlayerNAME(uid) == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("您不存在Yggdrasil账户", "Illegal Request", "Illegal Request"));
        }
        if (skin == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("RequestParam skin is NULL  MiaoWu~", "Illegal Request", "Illegal Request"));
        }
        if (!isValidPng(skin)) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("非法图像文件喵！", "Illegal Request", "Illegal Request"));
        }

        InputStream inputStream = skin.getInputStream();
        String hash = getHash(inputStream);
        inputStream.close();

        int type = 1;
        if (model != null) {
            type = switch (model) {
                case "default" -> 1;
                case "slim" -> 0;
                default -> 1;
            };
        }

        Path skinPath = Paths.get("Data/YggdrasilTexture/hash-" + hash);
        File file = new File(skinPath.toString());
        String uuid = yggdrasilRepository.GetPlayerUUID(uid);
        yggdrasilRepository.UpdateUseSkin(true, uid);
        yggdrasilPlayerRepository.UpdateSkinTexturesHash(hash, uuid);
        yggdrasilPlayerRepository.UpdateSkinTexturesType(type, uuid);

        if (!file.exists()) {
            TexturesList texturesList = new TexturesList();
            texturesList.setHash(hash);
            texturesList.setModel(type);
            texturesList.setType(true);
            texturesList.setUid(uid);
            texturesList.setCreate_time(System.currentTimeMillis());
            texturesListService.save(texturesList);

            try (InputStream inputStream1 = skin.getInputStream()) {
                BufferedImage src = ImageIO.read(inputStream1);
                ImageIO.write(src, "png", new File(String.valueOf(skinPath)));
            } catch (Exception e) {
                Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).warning(e.toString());
            }
        }

        return ResponseEntity.status(204).build();
    }

    public ResponseEntity<?> putCape(MultipartFile cape, HttpServletRequest request) throws Exception {
        String Authorization = request.getHeader("Authorization");
        String raw = Authorization.replace("Bearer ", "").replace(" ", "");
        String Token = utilset.decrypt(raw, privateKey);
        String uid = userDevicesRepository.findUidByToken(Token);
        if (uid == null || banUserRepository.existsByUidAndIsActiveTrue(uid)) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("账户状态异常，资料为只读，无法修改", "ForbiddenOperationException", "ForbiddenOperationException"));
        }
        if (yggdrasilRepository.GetPlayerNAME(uid) == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("您不存在Yggdrasil账户", "Illegal Request", "Illegal Request"));
        }
        if (cape == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("RequestParam cape is NULL  MiaoWu~", "Illegal Request", "Illegal Request"));
        }
        if (!isValidPng(cape)) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("非法图像文件喵！", "Illegal Request", "Illegal Request"));
        }

        InputStream inputStream = cape.getInputStream();
        String hash = getHash(inputStream);
        inputStream.close();

        Path capePath = Paths.get("Data/YggdrasilTexture/hash-" + hash);
        File file = new File(capePath.toString());
        String uuid = yggdrasilRepository.GetPlayerUUID(uid);
        yggdrasilRepository.UpdateUseCAPE(true, uid);
        yggdrasilPlayerRepository.UpdateSkinCAPETexturesHash(hash, uuid);

        if (!file.exists()) {
            TexturesList texturesList = new TexturesList();
            texturesList.setHash(hash);
            texturesList.setType(false);
            texturesList.setUid(uid);
            texturesList.setCreate_time(System.currentTimeMillis());
            texturesListService.save(texturesList);

            try (InputStream inputStream1 = cape.getInputStream()) {
                BufferedImage src = ImageIO.read(inputStream1);
                ImageIO.write(src, "png", new File(String.valueOf(capePath)));
            } catch (Exception e) {
                Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).warning(e.toString());
            }
        }

        return ResponseEntity.status(204).build();
    }

    private static String getHash(InputStream fis) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] byteArray = new byte[1024];
        int bytesCount;
        while ((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        }
        byte[] bytes = digest.digest();
        StringBuilder f = new StringBuilder();
        for (byte b : bytes) {
            f.append(String.format("%02x", b));
        }
        return f.toString();
    }

    public Boolean isValidPng(MultipartFile file) {
        try (InputStream fis = file.getInputStream()) {
            byte[] Infile = new byte[33];
            fis.read(Infile);
            fis.close();
            byte[] Header = {Infile[0], Infile[1], Infile[2], Infile[3], Infile[4], Infile[5], Infile[6], Infile[7],
                    Infile[8], Infile[9], Infile[10], Infile[11], Infile[12], Infile[13], Infile[14], Infile[15]};
            if (Arrays.equals(Header, PNG_HEADER)) {
                // 解析宽高，限制尺寸，防止高分辨率低熵 PNG 解压炸弹导致 OOM
                long width = ((long) (Infile[16] & 0xFF) << 24) | ((long) (Infile[17] & 0xFF) << 16)
                        | ((long) (Infile[18] & 0xFF) << 8) | (Infile[19] & 0xFF);
                long height = ((long) (Infile[20] & 0xFF) << 24) | ((long) (Infile[21] & 0xFF) << 16)
                        | ((long) (Infile[22] & 0xFF) << 8) | (Infile[23] & 0xFF);
                if (width <= 0 || height <= 0 || width > 1024 || height > 1024) {
                    return false;
                }
                byte[] ColorType = {Infile[25]};
                byte[] CompressionMethodAndInterlaceMethod = {Infile[26], Infile[27]};
                byte[] CRC = {Infile[29], Infile[30], Infile[31], Infile[32]};
                if (Arrays.equals(ColorType, PNG_ColorType) && Arrays.equals(CompressionMethodAndInterlaceMethod, PNG_CMIM)) {
                    byte[] GetCal = {Infile[12], Infile[13], Infile[14], Infile[15], Infile[16], Infile[17], Infile[18], Infile[19],
                            Infile[20], Infile[21], Infile[22], Infile[23], Infile[24], Infile[25], Infile[26], Infile[27], Infile[28]};
                    CRC32 crc32 = new CRC32();
                    crc32.update(GetCal);
                    return Long.toHexString(crc32.getValue()).equalsIgnoreCase(bytesToHex(CRC));
                } else return false;
            } else {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            int unsignedByte = b & 0xFF;
            String hex = Integer.toHexString(unsignedByte).toUpperCase();
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
