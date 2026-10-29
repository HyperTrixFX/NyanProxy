package moe.koseirin.nyanruaineo.services;


import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.repository.ApplicationRepository;
import moe.koseirin.nyanruaineo.repository.OAuthAppRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class OauthAppServices {


    private final OAuthAppRepository oAuthAppRepository;


    public OauthAppServices(OAuthAppRepository oAuthAppRepository) {
        this.oAuthAppRepository = oAuthAppRepository;
    }


    @Transactional
    public ResponseEntity<?> createAPP(){

        return null;
    }


}
