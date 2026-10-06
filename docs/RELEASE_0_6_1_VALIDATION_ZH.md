# 0.6.1 发布验收

2026-10-07。应用构建基线为 `b3937c3e2f064017566e65e7f29131c3507c1e1e`；最终发布提交只补充文档，不改变应用、算法或公共 API。版本 `0.6.1` / `12`，包名 `com.lightmeter.rawmeter`，最低 Android 9，目标 SDK 36。

- 既有验证：588 项单元 / Robolectric 测试通过，零失败、错误或跳过；Debug、Release 和 AAB 构建通过。Lint 零错误，65 项既有警告。
- 四个正式 APK 都通过 `apksigner verify --verbose --print-certs`，使用一个相同的 RSA 2048 发布证书，v2 签名有效；与已发布 0.6.0 一致。证书主体仅含项目名。
- 证书 SHA-256：`94d693638fbb0a55bc1c11e81fa5916d67572ee8e298dd4b08b315787a7f7a7a`。
- ABI 与文件名相符；所有 APK 均包含 27 个第三方许可条目。无 debuggable / testOnly 标记，无网络权限，仅含相机与可选位置权限。
- 逐个比对已验证的未签名构建：DEX、清单、资源和原生代码段一致。允许的差异仅为文本资源 CRLF 换行、`librawmeter.so` 的 GNU build-id 和 APK 签名数据。
- APK 中 OpenCV 原生库与固定 r2 AAR 完全一致。AAR SHA-256：`321c84621fe818e35cc7b6401953039bc784e4fe4cfb9d35690b4dbedf4d57cd`。
- 当前跟踪源码及 AAR、四个 APK 未检出开发者个人路径、已知设备标识、凭据或测试截图。17 个跟踪图片均为正式应用图标，未携带 EXIF / PNG 文本元数据；保留合法署名、许可证和本地测试材料，不改写历史。

## 正式 APK

| 文件 | 字节数 | MiB | SHA-256 |
| --- | ---: | ---: | --- |
| `lightstop-v0.6.1-arm64-v8a.apk` | 17529156 | 16.72 | `62ed58d21110ae5feb48be145e57b9fb273f7d4d3f100595ece9e66b6b49eea7` |
| `lightstop-v0.6.1-armeabi-v7a.apk` | 13240422 | 12.63 | `877bab6ccede170fd114d1953137142d1b56fd1304be80e605d4944bebeeb817` |
| `lightstop-v0.6.1-x86_64.apk` | 22053811 | 21.03 | `ceb8093ce381e27cef6296089d18e7bd48830c9a02ecf6eeaa8b767dda5f58f8` |
| `lightstop-v0.6.1-universal.apk` | 44664193 | 42.60 | `c3261971367f05eb9efe19fd082a599ba022278af35a7ce5f6547c86488e2c5a` |

发布附件仅包含这四个 APK、`SHA256SUMS.txt`、`LICENSE`、`NOTICE` 和 `THIRD_PARTY_NOTICES.md`。上传后逐一比对 GitHub 返回的大小与 SHA-256，再公开发布并设为最新版本。

## 16 KB 检查与实测边界

四个 APK 的 `zipalign -c -P 16 -v 4` 通过。arm64-v8a / x86_64 的全部原生库 ELF LOAD 对齐至少 16384；32 位 NDK 标准库保持其原有 4096 对齐，OpenCV 三个 ABI 都是 16384。

额外检查发现 64 位 NDK r27 `libc++_shared.so` 的 RELRO 末端不在 16 KB 边界。按 [Android linker 对 RELRO 范围向页边界取整的实现](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp)，复查其可写 LOAD 段：arm64 保护末端取整为 `0x144000`，后续可写段始于 `0x146e40`；x86_64 分别为 `0x13c000` 和 `0x13cf20`。取整扩大的范围只含段间填充，没有覆盖必须保持可写的数据；其他 64 位库也无此重叠。这是静态布局判断，不等于 16 KB 环境运行验证。参考 [Android 16 KB 检查指引](https://developer.android.com/guide/practices/page-sizes)。

本次沿用已完成的测试与构建验证，不承诺 r2 与 r4 性能一致。同机性能对照、16 KB 系统运行测试和更广泛设备测试仍待补充。0.6.0 的附件、标签与公开历史保持不变。
