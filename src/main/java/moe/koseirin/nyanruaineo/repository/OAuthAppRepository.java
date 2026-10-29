package moe.koseirin.nyanruaineo.repository;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.entity.OAuthApp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.io.Serializable;
@Repository
public interface OAuthAppRepository extends JpaRepository<OAuthApp, Integer>, Serializable {
    @Query(value = "SELECT COUNT(*) AS nums FROM OAuthApp where IsActive = true ")
    int GetAllApplication();






}
