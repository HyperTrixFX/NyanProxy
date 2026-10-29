package moe.koseirin.nyanruaineo.server.AIContorller;

import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.dto.AIChatRequestDTO;
import moe.koseirin.nyanruaineo.services.AIServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * @author KoseiRin_
 * awa
 */

@RestController
@RequestMapping("/api/v6/romyuai/")
public class ChatAPI {

    @Value("${ai.enable}")
    private boolean enable;

    public final AIServices aiServices;

    public ChatAPI(AIServices aiServices) {
        this.aiServices = aiServices;
    }


    @PostMapping("chat")
    public ResponseEntity<?> chat(@RequestBody AIChatRequestDTO dto, HttpServletRequest request){
        //TODO AI CHAT(webapi)
        if (false){
            return ResponseEntity.ok(aiServices.chat(request.getSession().getId(),dto.getMessage(),"null"));
        }else return ResponseEntity.status(204).build();

    }
}
