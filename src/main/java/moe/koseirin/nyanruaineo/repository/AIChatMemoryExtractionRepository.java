package moe.koseirin.nyanruaineo.repository;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.entity.AIChatMemoryExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;

@Repository
public interface AIChatMemoryExtractionRepository extends JpaRepository<AIChatMemoryExtraction, String>, Serializable {

    @Modifying
    @Transactional
    @Query("DELETE FROM AIChatMemoryExtraction e WHERE e.sessionId = ?1")
    int deleteBySessionId(String sessionId);
}
