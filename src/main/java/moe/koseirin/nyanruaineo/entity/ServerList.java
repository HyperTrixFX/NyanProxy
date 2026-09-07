package moe.koseirin.nyanruaineo.entity;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后端子服务器的鉴权记录。每个记录通过 {@link #uid} 关联到
 * {@code proxy.backend.servers} 中已配置的 {@code BackendServer.uid}，
 * 用于让子服务器插件通过 v7 接口（{@code /api/v7/backend/**}）与代理交互
 * （例如把封禁委托给代理处理）。
 * <p>
 * 服务端只保存访问令牌的 SHA-256 哈希，原始令牌仅在创建/重置时返回一次。
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServerList {

    /** 关联 {@code BackendServer.uid}，唯一标识一个后端子服务器的鉴权记录。 */
    @Id
    @Column(columnDefinition = "varchar(64)", nullable = false)
    private String uid;

    /** 后端子服务器展示名（冗余自 {@code BackendServer.name}，便于管理面板识别）。 */
    @Column(columnDefinition = "varchar(64)")
    private String serverName;

    /** 访问令牌的 SHA-256（hex）哈希；原始令牌只在创建/重置时返回一次。 */
    @Column(columnDefinition = "varchar(64)", nullable = false, unique = true)
    private String tokenHash;

    /** 是否允许该后端子服务器通过 v7 接口交互。 */
    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 最近一次成功鉴权的时间，可空。 */
    private LocalDateTime lastUsedAt;

    /** 创建/最近一次重置该凭据的管理员 uid。 */
    @Column(columnDefinition = "varchar(32)", nullable = false)
    private String createdBy;
}
