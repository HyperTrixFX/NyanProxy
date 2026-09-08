package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.NyanIDuserRepository;
import moe.koseirin.nyanruaineo.utils.ErrorUtils.ErrorResponse;
import moe.koseirin.nyanruaineo.utils.Respond;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Yggdrasil 静态资源读取（头像 / 纹理）业务逻辑，{@code /api/zako/res/{type}/{data}}。
 */
@Service
public class YggdrasilResourceService {

    private final BanUserRepository banUserRepository;
    private final NyanIDuserRepository nyanIDuserRepository;
    private final Respond respond;

    public YggdrasilResourceService(BanUserRepository banUserRepository, NyanIDuserRepository nyanIDuserRepository, Respond respond) {
        this.banUserRepository = banUserRepository;
        this.nyanIDuserRepository = nyanIDuserRepository;
        this.respond = respond;
    }

    public ResponseEntity<?> getResource(String type, String data) throws IOException {
        if (!banUserRepository.LEVE450TRUE(data).isEmpty()) {
            return respond.respond(MediaType.APPLICATION_JSON, 404,
                    new ErrorResponse("Not Found User Avatar", "Not Found", "Not Found"));
        }

        switch (type) {
            case "avatar": {
                if (data.length() != 32) {
                    return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("Not a valid universal identifier.", "Illegal Request", "Illegal Request"));
                }
                Boolean isGif = nyanIDuserRepository.IsGIFAvatar(data);
                Boolean enableGif = nyanIDuserRepository.EnableGIFAvatar(data);
                if (isGif && enableGif) {
                    int avatarId = nyanIDuserRepository.GIFAvatarID(data);
                    Path baseDir = Paths.get("Data/GIFAvatar").toRealPath();
                    Path avatarPath = baseDir.resolve("Data/GIFAvatar/" + avatarId + ".gif").normalize();
                    File file = new File(avatarPath.toString());
                    if (file.exists()) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.IMAGE_GIF)
                                .body(readFileBytes(file));
                    } else {
                        return loadPngAvatar(data);
                    }
                } else {
                    return loadPngAvatar(data);
                }
            }
            case "textures": {
                if (!data.matches("^[0-9a-fA-F]{1,64}$")) {
                    return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("Not a valid universal identifier.", "Illegal Request", "Illegal Request"));
                }

                Path baseDir = Paths.get("Data/YggdrasilTexture").toRealPath();
                Path texturePath = baseDir.resolve("hash-" + data).normalize();

                if (!texturePath.startsWith(baseDir)) {
                    return respond.respond(MediaType.APPLICATION_JSON, 400, new ErrorResponse("Invalid path.", "Illegal Request", "Illegal Request"));
                }

                File file = texturePath.toFile();
                if (file.exists() && file.isFile()) {
                    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(readFileBytes(file));
                } else {
                    return respond.respond(MediaType.APPLICATION_JSON, 404, new ErrorResponse("Not Found SKINTexture", "Not Found", "Not Found"));
                }
            }
            default:
                return respond.respond(MediaType.APPLICATION_JSON, 404,
                        new ErrorResponse("Not Found Resource", "Not Found", "Not Found"));
        }
    }

    private ResponseEntity<?> loadPngAvatar(String data) throws IOException {
        if (!data.matches("^[0-9a-fA-F]{32}$")) {
            return respond.respond(MediaType.APPLICATION_JSON, 400,
                    new ErrorResponse("Invalid avatar identifier.", "Illegal Request", "Illegal Request"));
        }
        Path baseDir = Paths.get("Data/UserAvatar").toRealPath();
        Path avatarPath = baseDir.resolve("UA-" + data + ".png").normalize();
        if (!avatarPath.startsWith(baseDir)) {
            return respond.respond(MediaType.APPLICATION_JSON, 400,
                    new ErrorResponse("Invalid path.", "Illegal Request", "Illegal Request"));
        }
        File file = avatarPath.toFile();
        if (file.exists() && file.isFile()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(readFileBytes(file));
        } else {
            return respond.respond(MediaType.APPLICATION_JSON, 404,
                    new ErrorResponse("Not Found User Avatar", "Not Found", "Not Found"));
        }
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return in.readAllBytes();
        }
    }
}
