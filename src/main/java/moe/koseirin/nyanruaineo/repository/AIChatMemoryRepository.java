package moe.koseirin.nyanruaineo.repository;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.entity.AIChatMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

@Repository
public interface AIChatMemoryRepository extends JpaRepository<AIChatMemory, Long>, Serializable {

    Optional<AIChatMemory> findByOwnerOpenidAndContentHash(String ownerOpenid, String contentHash);

    long countByOwnerOpenid(String ownerOpenid);

    List<AIChatMemory> findByOwnerOpenidOrderByImportanceDescUpdatedAtDesc(String ownerOpenid, Pageable pageable);

    /**
     * 在当前 union_openid 自己的记忆里做关键词检索。
     * 关键词同时匹配正文和关键词字段，按重要度、最近更新时间排序。
     */
    @Query("""
            SELECT m FROM AIChatMemory m
            WHERE m.ownerOpenid = :ownerOpenid
              AND (LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(m.keywords) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY m.importance DESC, m.updatedAt DESC
            """)
    List<AIChatMemory> searchByOwner(@Param("ownerOpenid") String ownerOpenid,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM AIChatMemory m WHERE m.ownerOpenid = ?1")
    int deleteAllByOwnerOpenid(String ownerOpenid);
}
