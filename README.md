# 内网代理中继 · ProxyRelay

连接配置与问题排查：[热点代理中继方案与排查](docs/热点代理中继方案与排查.md)。

无需 Root 的 Android TCP 中继，Android 8.0+，应用 ID `com.example.proxyrelay`。

笔记本 SOCKS5 → 手机热点 IP:17892 → 手机 VPN → 192.168.10.3:7892。

## 使用

1. 安装 `dist/ProxyRelay-debug.apk`，连接手机 VPN，开启系统热点。
2. 若 VPN 按应用分流，将“内网代理中继”加入 VPN 应用列表。
3. 在应用中配置上游 IPv4、端口、本机监听 IPv4、端口和连接超时，保存后启动。
4. 笔记本代理类型选择 SOCKS5，地址使用手机热点 IP，端口默认 17892。热点 IP 一般是笔记本当前默认网关；界面列出接口地址，但不自动判断哪一个是热点。
5. SOCKS5 账号密码在笔记本填写。内网域名启用代理端 DNS，如 curl 的 socks5h。
6. 应用或通知中点击停止，即关闭监听与现有连接。

支持配置保存、活动连接数、累计双向流量、接口地址复制、最近 120 条日志和前台通知。单次运行流量重新计数，日志仅保留在进程内存中。

## 边界

- 只支持 IPv4 地址配置与 TCP 转发；不支持 UDP、ping、自动系统全局代理。
- 不创建第二个 VPN，不修改路由；出站完全遵循 Android 为该应用选择的网络和 VPN 分流规则。
- 监听成功不等于上游可达；首次客户端连接时才连接上游。无 VPN 断线阻断保证；断线后应用重连可建立新连接，不恢复原有 TCP 会话。
- 默认监听 0.0.0.0，会暴露在所有可达 IPv4 接口。可改成热点 IP 限制监听范围；热点 IP 改变后需更新。仅向可信设备开放热点；中继本身不增加认证，沿用上游 SOCKS5 认证。
- 连接上限 64，连接超时默认 10 秒；不主动超时关闭空闲隧道。长期后台运行可能受厂商省电和系统休眠限制。
- 仅本地调试签名，尚未真机验证 UI、后台生存和 VPN 链路。

## 构建

本机已有 SDK/JDK 环境：

```bash
source /home/javen03/data/andriod/env.sh
cd /home/javen03/data/andriod/ProxyRelay
./gradlew :app:assembleDebug :app:lintDebug --offline
```

其他机器需安装 JDK 21、Android SDK 36.1 和 Build Tools 36.0.0，并设置 local.properties 中 sdk.dir。

## 中继核心测试

```bash
mkdir -p build/tests
javac -d build/tests app/src/main/java/com/example/proxyrelay/RelayEngine.java tests/RelayEngineTest.java
java -cp build/tests RelayEngineTest
```

测试使用本地回环连接，验证二进制双向转发、TCP 半关闭、流量计数、停止后端口复用、上游失败和活动连接关闭，不访问实际内网。

前台服务类型参考：[Android 官方文档](https://developer.android.com/develop/background-work/services/fgs/service-types)。
