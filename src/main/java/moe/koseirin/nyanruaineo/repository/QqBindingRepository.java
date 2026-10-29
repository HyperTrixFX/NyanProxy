package moe.koseirin.nyanruaineo.repository;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.entity.QqBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Optional;

@Repository
public interface QqBindingRepository extends JpaRepository<QqBinding, String>, Serializable {

    Optional<QqBinding> findByUid(String uid);

    boolean existsByUid(String uid);

    @Modifying
    @Transactional
    @Query("DELETE FROM QqBinding b WHERE b.unionOpenid = ?1")
    int deleteBinding(String unionOpenid);

    @Query("SELECT u.uid FROM QqBinding u WHERE u.unionOpenid = ?1")
    String findUid(String unionOpenid);
}
