# 老石头 Android v1.5.1 发布说明

> 版本：1.5.1（versionCode 8）
> 日期：2026-11-10
> 安装包：`artifacts/LaoShiTou-Android-v1.5.1-signed.apk`
> 说明书：`docs/APK_USER_GUIDE.md`

## 本版重点

- **关于页新增 PC 生态卡片**：展示老石头 PC 端入口、功能简介与下载入口。
- **PC 下载弹窗**：提供安装版与便携版下载，含蓝奏云链接与提取码，可一键复制密码。
- **PC 商店入口切换**：由爱发电改为闲鱼完整版 ¥299 链接，暂时移除爱发电入口。
- 其余播放、DSP、NAS/WebDAV、DLNA 功能与 v1.5 保持一致。

## 已知边界

- NAS 曲库为只读；DFF 仅缓存/本地播放，不支持 DMR 投送；
- Android DSD 为解码 + DSP + Oboe 共享输出，不是原生 DSD 直通；
- 同一 DLNA 连接只启用一个角色（发送者/接收者/离线）；
- 锁屏播放依赖系统省电策略，MIUI/HyperOS 需将应用设为“无限制”。

## 安装

```sh
adb install -r artifacts/LaoShiTou-Android-v1.5.1-signed.apk
```

签名与校验和见 `artifacts/SHA256SUMS-v1.5.1.txt`。
