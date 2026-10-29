package moe.koseirin.nyanruaineo.entity;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * QQ union_openid 与 NyanID 账号的一对一绑定。
 * <p>
 * 绑定流程只允许在私聊中发起：用户发送 {@code /bind <邮箱>}，系统向该邮箱发送 6 位验证码，
 * 用户再发送 {@code /bind <验证码>}。一个 QQ 只能绑定一个 NyanID，一个 NyanID 也只能绑定一个 QQ。
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "qq_binding",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_qq_binding_uid", columnNames = "uid")
        }
)
public class QqBinding {

    @Id
    @Column(name = "union_openid", columnDefinition = "varchar(128)", nullable = false)
    private String unionOpenid;

    @Column(name = "uid", columnDefinition = "varchar(32)", nullable = false)
    private String uid;

    @CreationTimestamp
    @Column(name = "bound_at", nullable = false, updatable = false)
    private LocalDateTime boundAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
