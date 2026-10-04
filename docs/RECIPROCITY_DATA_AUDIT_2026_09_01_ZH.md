# 胶片倒易率数据审计（2026-09-01）

## 审计范围

- 参考离散点：`胶片倒易率离散点_2026-08-24.csv`
- 参考方法库：`胶片倒易率失调计算数据库_2026-08-24.md`
- 应用资产：`app/src/main/assets/film_reciprocity_2026_08_24.json`
- `GitHub_Trending_Report.md` 只包含 GitHub 热门项目，与胶片倒易率没有字段或来源关系，因此不参与数据生成。

参考 CSV 和 Markdown 位于项目外，只作为只读输入。经联网复核后的修订保存在
`tools/film_reciprocity_audited_overrides_2026_09_01.json`；生成器默认加载该修订层，避免再次生成资产时恢复旧错误。

## 根因

原资产将 `RANGE`（只知道某段时间内无需补偿）当成了可用倒易率方法。运行时在范围内返回输入时间，超过范围则返回空值，所以表现为“短时间结果等于输入，长时间无数据”。

同时，CSV 中的 TABLE `grid` 行是旧分段幂插值生成的派生点，并非原始测量。现在只把 `Source=node` 的厂家/公开离散节点作为约束，在 `log2(Tm)-log2(Tc/Tm)` 补偿档位空间做保形三次曲线拟合；曲线通过每个原始节点且补偿量保持单调。无补偿边界处的补偿切线固定为 0，避免从 `Tc=Tm` 进入补偿区时出现肩部突变；内部节点使用保形切线，不产生局部回落或跨节点过冲。

## 用户录入与重置

- 内置胶片允许保存独立的用户倒易率覆盖，也可以给原本无数据的胶片补录数据；重置后恢复随 APK 提供的原设定。
- 可以新建自定义胶片，并选择离散点曲线、幂函数 `Tc=Tm^P`、固定 EV 或有限无需补偿范围。自定义胶片同时保留完整宽容度编辑能力。
- 离散点输入先转换为 `log2(Tm)` 曝光档位和 `log2(Tc/Tm)` 补偿档位，再进行保形三次拟合；不会直接在线性秒数上拟合。
- 保存前检查重复节点、校正时间短于测光时间、逆向节点和 24 小时上限。用户覆盖与厂家审计资产分开保存，不会改写原始数据库。

## 已修复数据

| 胶片/家族 | 旧状态 | 修订 | 依据 |
|---|---|---|---|
| ADOX CMS 20 II | RANGE，且 CSV 错写 `1s→1s` | TABLE：`1s→1.414s`、`10s→20s` | ADOX/ADOTECH IV 资料明确为 1 秒 +1/2 EV、10 秒 +1 EV |
| Fujifilm PRO 160NS | RANGE，只保留 2 秒边界 | FIXED_EV：4 秒起 +1/3 EV；2–4 秒间保持测光值 | Fujifilm 官方表明确 4 秒起 +1/3 EV |
| VISION3 50D/250D/500T、对应 Reflx Lab 与 SILBERSALZ35 分装 | 多数为 RANGE；50D 还被错误设为 P=1.0 | 加入 1 秒/10 秒节点和 CC10R 建议；超出节点标为估算 | SILBERSALZ35 同乳剂技术指南；Kodak 官方资料用于确认无补偿边界 |
| VISION2 50D/200T/250D/500T | RANGE，只保留无补偿边界 | 按 Kodak 历史 Field Guide/单片技术表恢复 1 秒、10 秒补偿节点及 CC10R 建议 | Kodak 历史厂家资料镜像；证据 A |
| VISION3 200T、SILBERSALZ35 200T | RANGE，只保留 1 秒边界 | 1–10 秒 +1/3 EV、无滤色片；10 秒以上明确标为估算 | Kodak 确认 ≤1 秒无需补偿；SILBERSALZ35 同乳剂品牌指南，证据 B |
| Rollei CROSSBIRD 200 | NONE | E-6：10 秒 +1 EV/CC07.5Y；100 秒 +2 EV/CC15Y+CC05C | Crossbird 可追溯为 Agfa Aviphot Chrome 200/RSX II 200 系分装；同乳剂证据 B |
| Kodak BW400CN | RANGE，整项被判作无数据 | 有限官方范围：1/10000–120 秒 `Tc=Tm`；滑杆止于 120 秒，不外推 | Kodak F-4036 直接资料；证据 A |
| EKTACOLOR PRO 160/400/800 | 错映射到旧 KODAK-COLOR-R1 | 映射到对应 Portra 160/400/800 社区实拍曲线 | 2026 EKTACOLOR PRO 是 Portra 对应乳剂的新分销名称；曲线本身仍标 C 级 |
| ILFORD PAN F+/DELTA 400/DELTA 3200/FP4+/HP5+ | 错把 0.5–1 秒纳入 `Tm^P`，会算出比测光时间更短的结果 | 按 2023 厂家说明改为 ≤1 秒不补偿；运行时同时禁止负补偿 | ILFORD Film Reciprocity Failure Compensation；证据 A |
| Rollei RPX 25/100/400 | 三个型号错误共用旧通用表，且精确上限与节点冲突 | 改为各型号当前厂家数据表的独立节点和上限 | Rollei 型号专用数据表；证据 A |
| Rollei Retro 80S/Superpan 200 | 厂家曲线拟合被错误标为 1000 秒内精确 | 精确范围收紧到厂家表最后一个 30 秒节点；之后明确标为估算 | Rollei 型号专用数据表 |

来源：

- [ADOX CMS 20 II / ADOTECH IV 数据表](https://www.fotoimpex.com/shop/images/products/media/30945_5_PDF-Datasheet.pdf)
- [Fujifilm PRO 160NS 官方数据表](https://asset.fujifilm.com/www/jp/files/2024-04/6b75b1c1cb4253623fb2cd7a56ff7443/datasheet_pro160ns_01.pdf)
- [Kodak VISION3 50D 官方资料](https://www.kodak.com/content/products-brochures/motion-picture/KODAK-VISION3-50D-5203-7203-brochure.pdf)
- [Kodak VISION3 500T 官方资料](https://www.kodak.com/content/pdfs/KODAK-VISION3-5219-7219-technical-information.pdf)
- [Kodak 2008 Cinematographer's Field Guide 历史镜像](https://www.cinematography.net/Files/H2_Field-Guide_9-22-08.pdf)
- [Kodak VISION2 250D H-1-5205t 历史技术表镜像](https://125px.com/docs/motionpicture/kodak/5205-Vision2-250D-tech.pdf)
- [Kodak VISION2 500T H-1-5218t 历史技术表镜像](https://www.cinematography.net/Files/5218.pdf)
- [Kodak VISION3 200T 官方技术表](https://www.kodak.com/content/pdfs/motion/KODAK-VISION3-200T-5213-7213-technical-information.pdf)
- [SILBERSALZ35 FAQ / 长曝节点](https://silbersalz35.com/faq/)
- [Agfa RSX II 200 历史厂家数据表](https://www.chrysis.net/wp-content/uploads/2020/08/Agfachrome_200_RSX-II.pdf)
- [Crossbird/Aviphot Chrome 200 乳剂追溯](https://filmphotography.eu/film/rollei-crossbird/)
- [Kodak BW400CN F-4036 历史厂家数据表镜像](https://www.bw-reeltime.com/tech/BW400cn.pdf)
- [Portra 160/400 社区实拍曲线与 1.34 拟合](https://www.flickr.com/groups/477426@N23/discuss/72157635197694957/)
- [Kodak EKTACOLOR PRO 产品页](https://www.kodak.com/en/still-film/product/professional/ektacolor-film/)
- [ILFORD 2023 倒易率公式与各胶片 P 因子](https://www.ilfordphoto.com/amfile/file/download/file/1964/product/681/)
- [Rollei RPX 25 型号专用数据表](https://www.rolleianalog.com/wp-content/uploads/2021/07/RPX25_Data-Sheet_EN_R210701.pdf)
- [Rollei RPX 100 型号专用数据表](https://www.rolleianalog.com/wp-content/uploads/2021/07/RPX100_Data-Sheet_EN_R210701.pdf)
- [Rollei RPX 400 型号专用数据表](https://www.rolleianalog.com/wp-content/uploads/2021/07/RPX400_Data-Sheet_EN_R210701.pdf)

## 仍无可计算长曝数据的 7 种胶片

以下胶片只有无补偿边界，或检索到的厂商/历史资料没有量化长曝节点。它们在倒易率选择器中置底、置灰，点击时提示无可计算数据；不使用通用指数伪造结果。

1. Kodak EKTACHROME 100D 5294/7294
2. Rollei ATP 1.1
3. Rollei BLACKBIRD 64
4. Rollei CN200
5. LUCKY COLOR 100
6. FILM Ferrania ORTO 50
7. FILM Ferrania P33 160

核查示例：Kodak 的 [EKTACHROME 100D](https://www.kodak.com/content/products-brochures/Film/KODAK-EKTACHROME-100D-COLOR-REVERSAL-FILM-5294-7294-datasheet-EN.pdf) 只确认至 1 秒无需补偿。BW400CN 虽没有 120 秒以上曲线，但厂家给出的完整 1/10000–120 秒无需补偿范围可以安全计算，因此改为“有限官方范围”，不再置灰。Rollei ATP 1.1 的历史数据表、Ferrania 产品页/使用指南和社区帖子均未提供可审计的长曝节点。

补充检索说明：VISION3 200T 官方表仍只公布至 1 秒，但 SILBERSALZ35 FAQ 对同一 5213 乳剂列出 1–10 秒 +1/3 EV，因此不再归为无数据。另一个测光应用声称 5213 长曝指数为 1.0，而论坛实拍常使用 Portra 的 1.34 指数，两者差异过大且缺少原始测试序列；本项目未采用这两个不可追溯的结论。

Crossbird 补充说明：其常见用法是 C-41 负冲以获得创意色偏，但 Agfa 原表的 CC 滤色片用于 E-6 正冲。应用中的时间补偿可供两种工艺参考，CC07.5Y/CC15Y+CC05C 只应在 E-6 中作为起点，不能承诺 C-41 负冲后的中性色彩。

## 产品边界

- 倒易率计算器只展示最终校正时间严格小于 24 小时的输入刻度。
- 超出最后一个精确节点但仍在 24 小时结果范围内的值标记为“估算”。
- `NONE` 和 `RANGE` 不再视为可选择的倒易率曲线；`BOUNDED_UNCHANGED` 只在厂家明确范围内可选，且不允许外推。
- 颜色负片的单一时间曲线不能修复各感色层不同步导致的色偏；有滤镜节点时仍显示厂家/品牌滤镜建议。
