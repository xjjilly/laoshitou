# 老石头 v1.5 开源发布清单（上传用）

> 状态：v1.5 发布清单（2026-11-06）。
> 本清单用于把 v1.3 发布到 GitHub（或其它代码托管）。

## 1. 发布身份

- 版本：`1.5`（versionCode 7）
- 许可证：GNU GPLv3（见 `LICENSE`）
- 签名证书 SHA-256：
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
- 发布日期：2026-11-02

## 2. 发布产物

| 文件 | 说明 |
|---|---|
| `artifacts/LaoShiTou-Android-v1.5-signed.apk` | 签名 release APK |
| `artifacts/laoshitou-1.5-source.tar.gz` | GPLv3 对应源码包（含 FFmpeg 7.1 源码、第三方许可证、文档与截图） |
| `artifacts/SHA256SUMS-v1.5.txt` | APK + 源码包校验和 |
| `RELEASE_NOTES_v1.5.md` | 发布说明（可直接作为 GitHub Release 描述） |
| `docs/APK_USER_GUIDE.md` | 小白版使用说明书 |
| `docs/screenshots/` | 5 张界面截图（合成示例音轨，无版权内容） |
| `THIRD_PARTY_NOTICES.md` / `third_party/licenses/` | 第三方许可与来源 |

APK SHA-256 在 `SHA256SUMS-v1.5.txt` 与用户手册中均有记录；源码包哈希以
打包后生成的 `SHA256SUMS-v1.5.txt` 为准（源码包内不写入自身哈希）。

## 3. 上传前检查

- [ ] `git status` 确认改动范围；`.gitignore` 已排除 `keystore.properties`、
      `local.properties`、`*.jks`、`artifacts/`、构建目录。
- [ ] 源码包不包含 `keystore.properties`、`*.jks`、`local.properties`、构建产物；
      已排除 `samples/ir/sadie2/` 等大体积可再下载素材。
- [ ] 源码包包含：`app/`、`platform-android/`、`core-dsp/`、`third_party/`、
      `docs/`（含 `APK_USER_GUIDE.md`、`screenshots/`、测试报告/规划）、`LICENSE`、
      `THIRD_PARTY_NOTICES.md`、`RELEASE_NOTES_v1.5.md`、FFmpeg 源码 tarball。
- [ ] 仓库中没有任何版权音乐/封面；截图仅使用合成的“示例音轨”。
- [ ] 截图不含账号、个人通知、状态栏个人信息；状态栏/导航栏已裁掉。
- [ ] FFmpeg 配置仍为 `--disable-gpl --disable-nonfree --disable-version3`，
      无 `libfdk_aac`；第三方声明与实际依赖一致。
- [ ] 运行 `sha256sum -c artifacts/SHA256SUMS-v1.5.txt` 全部 OK。
- [ ] 在真机安装 APK，完成：启动、放歌、DLNA 发送/接收、DSD/DFF 说明一致。

## 4. 建议上传步骤

1. 确认改动后提交并打标签：

   ```sh
   git add -A
   git commit -m "release: v1.5 — NAS/SQL 曲库、DLNA 双向与锁屏保活"
   git tag -a v1.5 -m "LaoShiTou v1.5"
   git push origin main --tags
   ```

2. 在 GitHub 创建 Release：标签选 `v1.5`，标题
   `LaoShiTou v1.5 — NAS/SQL 曲库、DLNA 双向与锁屏保活`，描述粘贴
   `RELEASE_NOTES_v1.5.md`。

3. 上传附件：

   - `artifacts/LaoShiTou-Android-v1.5-signed.apk`
   - `artifacts/laoshitou-1.5-source.tar.gz`
   - `artifacts/SHA256SUMS-v1.5.txt`
   - 可选：`docs/screenshots/` 中 5 张 PNG

4. 在 Release 描述中链接：
   `docs/APK_USER_GUIDE.md`（使用说明）、`docs/screenshots/`（截图）、
   `THIRD_PARTY_NOTICES.md`（第三方声明）。

5. 上传后重新下载附件并核对 SHA-256。

## 5. 已知说明（必须在发布页保留）

- **DSD（DSF）CPU 约 80% 单核**：DSD64 转换 PCM + 重采样 + DSP 的算力换音质
  代价，属正常；可降低重采样质量或使用直通。
- **DFF 不支持 DLNA 投送**：DMR 原生解码用 FFmpeg 7.1（无 DSDIFF demuxer）；
  本地播放支持 DFF，投送请先转 DSF。DSF 可正常投送。
- **第三方渲染器首测（Kodi 21.3）**：发现、投送、暂停/继续、Seek、音量、停止、
  离线恢复通过；`CurrentURIMetaData` 已升级为标准 DIDL-Lite。其余电视/音箱与
  控制点矩阵待补。
- **MIUI/HyperOS 同机渲染器**：若投送目标运行在本机（如 Kodi），请把该渲染器
  应用的「省电策略」设为「无限制」，否则后台 UPnP 服务会被冻结。

## 6. 当前环境备注

- `gh` CLI 未安装，GitHub MCP server 连接失败；发布使用 GitHub REST API 与
  本机 git 凭据（`git credential fill`），不打印令牌。
- 发布前确认 `RELEASE_NOTES_v1.5.md`、`artifacts/` 与源码包均已生成。
