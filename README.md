# NyanProxy 项目文档

NyanProxy（`moe.koseirin.nyanruaineo`）是一个基于 Spring Boot 的一体化Minecraft代理服务，主要包含：

- **用户系统**：注册、登录、二次验证（2FA）、设备管理、资料修改、头像、密码找回。
- **Yggdrasil 外置登录**：完整实现 `authserver` / `sessionserver` / `textures`，可作为 Minecraft 外置登录服。
- **Minecraft 代理（Proxy）**：基于 Netty 实现的 BungeeCord 轻量移植跨服代理，支持多后端路由、子服务器热更新、TabList 拦截、MOTD、封禁、防火墙、权限节点、插件消息（BungeeCord 频道）。
- **QQ 机器人（Qbot）**：`/status`、`/list`、`/bc`、AI 聊天。
- **AI 聊天接口**：`/api/v6/romyuai/chat`。

## 文档目录

| 文档                                                                | 说明 |
|---------------------------------------------------------------------| --- |
| [01-项目概述与架构](./doc/01-项目概述与架构.md)                     | 技术栈、模块结构、核心概念 |
| [02-环境搭建与部署](./doc/02-环境搭建与部署.md)                         | 依赖、构建、运行、目录结构 |
| [03-配置参考](./doc/03-配置参考.md)                                     | `application.yml`、`application.properties`、`SystemConfig`（`proxy.*`）配置 |
| [04-API接口文档](./doc/04-API接口文档.md)                               | 全部 REST API 的用法 |
| [05-Minecraft代理使用](./doc/05-Minecraft代理使用.md)                   | 代理命令、权限、封禁、防火墙、TabList、插件消息 |
| [06-MinecraftProxy更新维护指南](./doc/06-MinecraftProxy更新维护指南.md) | 代理代码结构与日常维护 |
| [07-控制台命令](./doc/07-控制台命令.md)                                 | 服务端控制台命令 |
| [08-权限与封禁系统](./doc/08-权限与封禁系统.md)                         | 权限节点、封禁、自动解封 |
| [09-常见问题与故障排查](./doc/09-常见问题与故障排查.md)                 | FAQ 与排障 |
| [10-QQ机器人](./doc/10-QQ机器人.md)                                 | QQ 机器人（Qbot） |
| [11-AI服务](./doc/11-AI服务.md)                                     | AI 聊天服务（AIServices） |
| [12-用户管理与工单系统](./doc/12-用户管理与工单系统.md)             | V3 用户管理 + 工单（Ticket）系统 |
| [14-v7后端服务器接口](./doc/14-v7后端服务器接口.md)                 | 子服务器插件对接代理的 v7 接口（封禁/踢出/广播等）与凭据管理 |

## 快速开始

1. 配置 `config/application.yml`（数据库 / Redis / 邮箱 / 域名等）。
2. 启动应用，首次运行会自动创建 `config/`、`Data/`、`plugins/` 等目录，并将 `application.cfg` 复制为 `config/application.yml`（如果不存在）。
3. 用 `/help` 查看控制台命令；用 `/op <uid>` 授予管理员（root）权限。
4. 在 `SystemConfig` 表（或通过代理管理 API）配置 `proxy.backend.servers` 等项后即可启动代理。


## 关于项目
1. 您可以自由编辑该项目来满足您的业务需求，且您需要将您修改后的版本进行开源
2. 本项目是对BungeeCord与SpringBoot的整合，并添加了亿些小巧思



再不写文档就真看不懂了awa