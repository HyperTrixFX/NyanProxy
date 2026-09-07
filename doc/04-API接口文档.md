# API 接口文档

## 通用约定

### 认证头

大部分需要登录态的接口使用：

```
Authorization: Bearer <RSA 加密后的 accessToken>
Event: <操作类型>
```

- `accessToken`：登录后返回的 32 位原始 token（`UserDevices.Token`）。
- 传输时用 RSA **公钥** 加密（客户端），服务端用 `yggdrasil.privateKey` 解密。
- `Event` 头与 HTTP 方法需匹配（由 `AuthenticateCheck` 拦截器校验）：

| Event | 含义 | 对应 HTTP 方法 |
| --- | --- | --- |
| `0` | 获取信息 | GET |
| `1` | 上传数据 | POST |
| `2` | 上传资源 | PUT |
| `3` | 删除 | DELETE |

### 拦截器覆盖范围（AuthenticateCheck）

`/api/zako/v1/userdata`、`/api/zako/v1/userinfo`、`/api/zako/v1/bma`、`/api/zako/v1/cl`、`/api/zako/v1/user/devices`、`/api/yggdrasil/open/account`、`/api/zako/v1/user/violation/history`、`/api/v3/zako/administration/validate`、`/api/zako/v1/user/2fa/open2fa`、`/api/zako/v1/user/2fa/close2fa`、`/api/yggdrasil/textures/**`、`/api/zako/v3/**`。

### 响应格式

- 成功多为 `200` + JSON；部分为 `204 No Content`。
- 错误多返回 `ErrorResponse`（`{ "error": "...", "message": "...", "timestamp": "..." }`）或 `Respond.respond(...)` 生成的 JSON。

---

## 根路径

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 返回 `Ok`，并设置 `X-Authlib-Injector-API-Location: /api/yggdrasil` |
| POST | `/` | 同上（JSON `Ok`） |

---

## V1：用户认证与资料（`/api/zako/v1`）

### 注册 / 登录

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/register` | `RegisterDTO` | 注册（发送验证邮件） |
| POST | `/verification` | `RegisterConfirmDTO` | 用邮件验证码完成注册 |
| POST | `/login` | `LoginDTO` | 邮箱 + 密码登录 |
| POST | `/2fa` | `LoginDTO` | 2FA 二次验证登录 |

**RegisterDTO**

```json
{ "email": "...", "password": "...", "username": "...", "idempotencyKey": "..." }
```

**RegisterConfirmDTO**

```json
{ "code": "..." }
```

**LoginDTO**

```json
{ "email": "...", "password": "...", "idempotencyKey": "...", "have2fa": false, "token": "...", "verifyCode": "..." }
```

### 微软登录

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/microsoft/newlogin` | - | 重定向到微软登录 |
| POST | `/microsoft/newlogin` | `code`（必填）、`id_token`（可选）、`state`（必填） | 微软 OAuth 回调 |

### 忘记 / 重置密码

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/forgetpwd?email=` | query `email` | 发送找回密码邮件 |
| POST | `/resetpwd` | `ResetPwdDTO` | 用 token + code + 新密码重置 |

**ResetPwdDTO**

```json
{ "token": "...", "code": "...", "password": "..." }
```

### 用户资料

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| POST | `/userdata` | `UserDataDTO` | 按 `action` 执行资料操作（改昵称/用户名/描述等） |
| PUT | `/userdata` | multipart `avatar` | 上传头像 |

**UserDataDTO**

```json
{ "action": 0, "nickname": "...", "username": "...", "description": "...", "code": "..." }
```

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/userinfo` | -（需认证） | 获取当前用户完整资料 |

### 安全 / 设备

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| POST | `/user/2fa/open2fa` | -（需认证） | 开启 2FA |
| POST | `/user/2fa/close2fa` | -（需认证） | 关闭 2FA |
| GET | `/user/devices` | -（需认证） | 查询登录设备 |
| DELETE | `/user/devices` | `DeleteDevicesDTO` | 注销设备 |

**DeleteDevicesDTO**

```json
{ "value": "设备标识" }
```

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/user/violation/history` | -（需认证） | 违规/封禁历史 |

> **活跃异常（只读）**：账号存在生效中的封禁时仍可登录，但为只读——`/userdata`（改昵称/用户名/简介/绑定/头像）、`/user/2fa/*`（开关 2FA）、`/forgetpwd` + `/resetpwd`（找回/重置密码）均返回 `403 账户状态异常，资料为只读…`。详见 [08-权限与封禁系统](./08-权限与封禁系统.md)。

### 工单（用户自助，`/api/zako/v1/ticket`）

只需 `Authorization: Bearer <token>`（token→uid），不校验管理权限节点；且**不挂 `AuthenticateCheck`**，以便被封用户也能提交禁封申诉。

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/ticket` | `TicketCreateDTO` | 提交工单 |
| GET | `/ticket` | - | 我的全部工单 |
| GET | `/ticket/{ticketId}` | - | 我的某一单（校验归属） |

**TicketCreateDTO**

```json
{ "type": 1, "description": "申诉内容" }
```

- `type` 仅接受 1（禁封申诉）/ 2（开发者申请）/ 3（账号安全申诉）；`description` 必填且 ≤ 155 字符。
- 提交规则：三类工单都按「同类型」查重——已有未结束的同类型工单时禁止重复申请（返回 409）；`type=1` 还需账户存在活跃异常（生效中的封禁，否则 403）；`type=2` 若用户已是开发者（`IsDeveloper=true`）则禁止（返回 409）。详见 [12-用户管理与工单系统](./12-用户管理与工单系统.md)。

### 占位

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/devices` | 目前返回 `null`（未实现） |

---

## V2：公开信息（`/api/zako/v2`）

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/userinfo/{uuid}` | path `uuid` | 公开用户信息（昵称、exp、description、uid…） |
| POST | `/searchuser` | `UserResponseDTO` | 搜索用户 |
| GET | `/server` | - | 服务器信息 |

**UserResponseDTO**

```json
{ "value": "关键词" }
```

> `StaticResourceController`（`/api/zako/v2`）目前为空。

---

## V3：管理（`/api/zako/v3`）

### 代理管理（`/api/zako/v3/proxy`，需 `minecraftproxy.admin` 权限）

所有接口都需要 `Authorization: Bearer <token>`；由于 `/api/zako/v3/**` 会经过 `AuthenticateCheck` 拦截器，还需携带 `Event` 头（`0`/`1`/`2`/`3`，与 HTTP 方法匹配）。服务端解析出 uid 后校验 `minecraftproxy.admin` 权限；无权限返回 `403 Permission denied!`。

#### 后端服务器管理

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/servers` | - | 列出所有后端服务器（含运行时在线状态） |
| POST | `/servers` | `BackendServer` | 新增后端服务器 |
| PUT | `/servers/{uid}` | `BackendServer` | 更新指定后端 |
| DELETE | `/servers/{uid}` | - | 删除指定后端 |

**BackendServer**（写入 `POST`/`PUT` 请求体）

```json
{ "uid": "lobby-001", "priority": 1, "name": "lobby", "host": "localhost", "port": 25566 }
```

**GET `/servers` 响应**（在静态字段基础上附加运行时状态）：

```json
{
  "uid": "lobby-001", "priority": 1, "name": "lobby", "host": "localhost", "port": 25566,
  "online": true, "onlineCount": 3,
  "players": [ { "username": "Notch", "uuid": "..." } ]
}
```

- `online`：后端是否在线（TCP 探测可达，5 秒缓存）。
- `onlineCount`：当前连接到该后端的玩家数量。
- `players`：当前连接到该后端的玩家列表（`username` + `uuid`）。

#### 代理配置管理

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| GET | `/config` | - | 列出可编辑的代理配置项（`key` + `value` + `type`） |
| PUT | `/config` | `{ "配置键": "值", ... }` | 批量更新配置（严格类型校验，写后热重载） |

可编辑的配置键（不含 `proxy.backend.servers`，该键走 `/servers` 专用接口）：

| 键 | 类型 | 校验 |
| --- | --- | --- |
| `proxy.port` | int | 1–65535 |
| `proxy.maxPlayers` | int | ≥ 0 |
| `proxy.name` | string | 非空 |
| `proxy.online-mode` | boolean | `true`/`false` |
| `proxy.ip-forward` | boolean | `true`/`false` |
| `proxy.forge-support` | boolean | `true`/`false` |
| `proxy.motd` | json | 可解析为 `MotdConfig` |
| `proxy.tablist` | json | 可解析为 `TabListConfig` |
| `proxy.firewall` | json | 可解析为 `FirewallConfig` |
| `proxy.kick-message` | json | 可解析为 `KickMessageConfig`（普通踢出模板） |
| `proxy.ban-message` | json | 可解析为 `BanMessageConfig`（封禁屏幕模板） |

所有值长度 ≤ 800（`SystemConfig.configValue` 为 `varchar(800)`）。

**PUT `/config` 示例**

```json
{ "proxy.name": "NekoProxy", "proxy.port": "25566", "proxy.online-mode": "false" }
```

- JSON 配置项的值须是 JSON 字符串（例如 `"proxy.motd": "{\"lines\":[...]}"`）。
- 写库后调用缓存重载使读取方立即使用新值；`proxy.port` 等监听相关项需重启代理才真正生效。

#### 玩家管理（转移 / 踢出）

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/players/transfer` | `PlayerTransferDTO` | 将在线玩家转移到指定子服务器（先校验玩家与目的服务器状态） |
| POST | `/players/kick` | `PlayerKickDTO` | 踢出在线玩家（原因可编辑） |

**PlayerTransferDTO**

```json
{ "player": "Notch", "targetServer": "survival" }
```

- `player`：用户名或 UUID（服务端自动识别）。
- `targetServer`：服务器名或 uid（服务端自动识别）。

**PlayerKickDTO**

```json
{ "player": "Notch", "reason": "违规行为" }
```

- `player`：用户名或 UUID。
- `reason`：可选，缺省为「Kicked by an administrator」，最长 256 字符。

#### 权限管理

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/permissions` | `uid` | 查询用户权限节点集合 |
| POST | `/permissions` | `uid`、`node` | 授予权限节点 |
| DELETE | `/permissions` | `uid`、`node` | 回收权限节点 |

### 用户管理（`/api/zako/v3/users`，需 `nyanid.admin.user` 权限）

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/users` | query `keyword`（可选）、`page`、`size` | 分页列出 / 搜索用户 |
| GET | `/users/banned` | query `page`、`size` | 分页列出当前生效封禁的用户 |
| GET | `/users/developers` | query `page`、`size` | 分页列出开发者用户 |
| GET | `/users/{uid}` | path `uid` | 用户详情 |
| PUT | `/users/{uid}` | `UserEditDTO` | 编辑资料（只更新非空字段） |
| PUT | `/users/{uid}/active` | query `active` | 账号启停 |
| PUT | `/users/{uid}/developer` | query `active` | 设置 / 取消开发者标志 |
| POST | `/users/{uid}/ban` | query `reason`、`type`、`expire`（均可选） | 封禁（默认死封 type=6） |
| DELETE | `/users/{uid}/ban` | - | 解封该账号全部生效封禁 |
| GET | `/users/{uid}/permissions` | - | 查询权限节点集合 |
| POST | `/users/{uid}/permissions` | query `node` | 授予权限节点 |
| DELETE | `/users/{uid}/permissions` | query `node` | 回收权限节点 |

**UserEditDTO**

```json
{ "nickname": "...", "description": "...", "exp": 100, "isDeveloper": false }
```

> 列表与详情只返回安全字段，不暴露密码/密钥/设备 token。

### 工单管理（`/api/zako/v3/tickets`，需 `nyanid.admin.ticket` 权限）

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/tickets` | query `status`（可选）、`page`、`size` | 分页列出工单，可按状态过滤 |
| GET | `/tickets/{ticketId}` | path | 查看一单 |
| PUT | `/tickets/{ticketId}` | `TicketHandleDTO` | 处理工单（状态 / 处理人 / 回复），仅 `PENDING` 可处理 |

**TicketHandleDTO**

```json
{ "status": "APPROVED", "handlerUid": "<管理员uid>", "reply": "审核通过" }
```

- 仅 `PENDING` 可处理；非 `PENDING`（含 `PROCESSING`）返回 `409 工单已处理，无法再次修改`。

> 详见 [12-用户管理与工单系统](./12-用户管理与工单系统.md)。

### 其他 V3 控制器

`AdministrationController`、`ServerManagerController`、`ServerSyncController`（均在 `/api/zako/v3`）目前为空占位。

---

## V4 / V5（占位）

- `ApplicationController`、`Oauth2Controller`（`/api/zako/v4`）：空。
- `DevsManager`（`/api/zako/v5` 相关）：空。

---

## AI 聊天（`/api/v6/romyuai`）

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/chat` | `AIChatRequestDTO` | 发送聊天消息；`ai.enable=false` 时返回 `204` |

**AIChatRequestDTO**

```json
{ "message": "..." }
```

---

## Yggdrasil 外置登录

### 元信息（`/api/yggdrasil`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` 或 `""` | 返回 Yggdrasil 元信息（meta、skinDomains、signaturePublickey），并设置 `X-Authlib-Injector-API-Location` |

### 账号（`/api/yggdrasil/open/account`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/open/account` | 为当前登录用户创建 Yggdrasil 角色（需 `Authorization`）；已存在返回 404 |

### Authserver（`/api/yggdrasil/authserver`）

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/authenticate` | Yggdrasil authenticate JSON | 登录（`username`=邮箱、`password`、`clientToken`、`requestUser`、`agent`） |
| POST | `/refresh` | `accessToken`、`clientToken`、`requestUser`… | 刷新 accessToken |
| POST | `/validate` | `accessToken`、`clientToken` | 校验 accessToken（成功 204） |

### Sessionserver（`/api/yggdrasil/sessionserver/session/minecraft`）

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| POST | `/join` | `accessToken`、`selectedProfile`、`serverId` | 客户端加入服务器时登记会话 |
| GET | `/hasJoined?username=&serverId=` | query | 服务端校验玩家会话 |
| GET | `/profile/{uuid}` | path `uuid`（可带 `unsigned` query） | 获取角色 profile（含 textures 属性） |

### Textures（`/api/yggdrasil/textures`）

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| PUT | `/skin` | multipart `skin`（+`model`=default/slim） | 上传皮肤 |
| PUT | `/cape` | multipart `cape` | 上传披风 |

（均需 `Authorization`，仅 PNG 有效）

---

## 资源（`/api/zako/res`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/zako/res/{type}/{data}` | `type` 为 `avatar`（32 位 uid）或 `textures`（纹理 hash）；返回对应图片 |

---

## 代理插件消息（BungeeCord 频道）

后端（Spigot/Paper）通过 `BungeeCord` 频道向代理发送的**子命令**（非 HTTP，但属于代理 API）：

| 子命令 | 说明 |
| --- | --- |
| `ForwardToPlayer` | 转发插件消息到指定玩家的后端 |
| `Forward` | 转发到 `ALL` / `ONLINE` / 指定服务器 |
| `Connect` / `ConnectOther` | 让玩家连接到指定服务器（已做去重） |
| `GetPlayerServer` | 查询玩家所在服务器 |
| `IP` / `IPOther` | 查询玩家 IP |
| `PlayerCount` | 查询在线人数 |
| `PlayerList` | 查询玩家列表 |
| `GetServers` / `GetServer` | 查询服务器列表 / 当前服务器 |
| `Message` / `MessageRaw` | 发送消息 |
| `UUID` / `UUIDOther` | 查询玩家 UUID |
| `ServerIP` | 查询服务器地址 |
| `KickPlayer` / `KickPlayerRaw` | 踢出玩家 |
