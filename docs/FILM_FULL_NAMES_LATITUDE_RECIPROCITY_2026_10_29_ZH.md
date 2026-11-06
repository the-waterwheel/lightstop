# 胶片全名清单：倒易率 / 宽容度数据库

> 生成日期：2026-10-29
> 
> 统计范围：应用内胶片库合并后的全部胶片，共 **285 款**（基础库 193 款 + 增补库 92 款）。
> 名称口径：厂商 + 型号；型号本身已含品牌前缀时不再重复厂商名（如 `ROLLEI RPX 100`、`Reflx Lab PRO100`）。
> 宽容度口径：相对 18% 灰（Zone V）的 EV 偏移，格式为「阴影端 / 高光端」。

## 一、数据来源

- 宽容度基础库：[film_latitude_2026_08_17.json](../app/src/main/assets/film_latitude_2026_08_17.json)
- 数据库增补与修订：[film_database_updates_2026_09_08.json](../app/src/main/assets/film_database_updates_2026_09_08.json)
  - `profilePatches`：9 条既有胶片名称/参数修订（已合并）
  - `profiles`：92 款新增胶片（ID 194–285）
- 倒易率基础库：[film_reciprocity_2026_08_24.json](../app/src/main/assets/film_reciprocity_2026_08_24.json)
- 倒易率增补方法/映射：同上 `film_database_updates_2026_09_08.json` 的 `methods` 与 `films`

合并规则与应用运行逻辑一致：先以基础库 193 款为底，应用 `profilePatches`，再追加 `profiles` 的 92 款；倒易率映射以增补库覆盖基础库。最终 **285 款胶片全部具备倒易率映射**，无孤立数据。

## 二、统计概览

- 胶片总数：**285** 款
- 厂商：**21** 家
- 黑白胶片：**97** 款；彩色胶片：**188** 款
- 在产：**126** 款；停产：**159** 款
- 宽容度范围：阴影端 **-5.5 ~ -2 EV**，高光端 **+1.5 ~ +7.5 EV**
- 倒易率数据覆盖：**285 / 285（100%）**

### 2.1 厂商分布

| 厂商 | 胶片数 | 在产 | 停产 |
| --- | ---: | ---: | ---: |
| Kodak | 104 | 30 | 74 |
| Fujifilm | 40 | 9 | 31 |
| ILFORD / HARMAN | 18 | 17 | 1 |
| Konica | 18 | 0 | 18 |
| Lomography | 17 | 16 | 1 |
| Rollei | 12 | 8 | 4 |
| Agfa | 11 | 0 | 11 |
| AgfaPhoto | 10 | 2 | 8 |
| FOMA | 9 | 9 | 0 |
| ADOX | 8 | 6 | 2 |
| 乐凯 / LUCKY | 7 | 3 | 4 |
| FILM Ferrania | 6 | 3 | 3 |
| CineStill | 5 | 5 | 0 |
| ORWO | 5 | 5 | 0 |
| Reflx Lab | 5 | 5 | 0 |
| SILBERSALZ35 | 4 | 4 | 0 |
| 上海 / SHANGHAI | 2 | 2 | 0 |
| BERGGER | 1 | 1 | 0 |
| Japan Camera Hunter | 1 | 1 | 0 |
| 代代红 / DAIDAIHONG | 1 | 0 | 1 |
| 太行 / TAIHANG | 1 | 0 | 1 |
| **合计** | **285** | **126** | **159** |

### 2.2 倒易率数据类型分布

| 类型 | 说明 | 胶片数 |
| --- | --- | ---: |
| 节点表 | 厂家公布离散时间节点，节点间按对数时间插值 | 142 |
| 幂律 | 按 Tc = Tm^P 幂函数修正 | 100 |
| 区间内无补偿 | 厂家公布的无补偿时间上限，超出范围不外推 | 40 |
| 固定补偿 | 按固定 EV 值补偿 | 3 |

## 三、分厂商胶片全名清单

说明：ID 为数据库内部编号；状态「停产」依据 `discontinued` 字段；倒易率列为该胶片所引用的方法类型。

### Kodak（104 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 265 | Kodak PROFESSIONAL Copy Film 4125 | 12 | 黑白技术负片 | -2.5 / +4 | 停产 | 幂律 |
| 25 | Kodak KODACHROME 25 | 25 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 253 | Kodak ROYAL GOLD 25 Film | 25 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 6 | Kodak TECHNICAL PAN 25 | 25 | 黑白技术负片 | -3.5 / +6.5 | 停产 | 节点表 |
| 3 | Kodak PANATOMIC-X 32 | 32 | 黑白负片 | -3.5 / +7.5 | 停产 | 幂律 |
| 38 | Kodak VISION2 50D 5201/7201 | 50 | 彩色电影负片 | -4.5 / +5.5 | 停产 | 节点表 |
| 42 | Kodak VISION3 50D 5203/7203 | 50 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 230 | Kodak EKTACHROME 64 Professional Film (EPR) | 64 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 231 | Kodak EKTACHROME 64T Professional Film (EPY) | 64 | 彩色反转片 | -2.5 / +2 | 停产 | 区间内无补偿 |
| 26 | Kodak KODACHROME 64 | 64 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 10 | Kodak E100G | 100 | 彩色反转片 | -2.5 / +2.5 | 停产 | 节点表 |
| 11 | Kodak E100GX | 100 | 彩色反转片 | -2.5 / +2.5 | 停产 | 节点表 |
| 16 | Kodak EKTACHROME 100 Plus Professional Film (EPP) | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 15 | Kodak EKTACHROME 100 Professional Film (EPN) | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 13 | Kodak EKTACHROME 100D Color Reversal Film 5294/7294 | 100 | 彩色电影反转片 | -2.5 / +2.5 | 在产 | 区间内无补偿 |
| 14 | Kodak EKTACHROME E100 | 100 | 彩色反转片 | -2.5 / +2.5 | 在产 | 节点表 |
| 20 | Kodak EKTAPAN 100 | 100 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 23 | Kodak EKTAR 100 | 100 | 彩色负片 | -3.5 / +6 | 在产 | 幂律 |
| 240 | Kodak ELITE Chrome Extra Color 100 Film | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 区间内无补偿 |
| 243 | Kodak GOLD 100 Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 28 | Kodak KODACOLOR 100 | 100 | 彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 8 | Kodak PRO IMAGE 100 | 100 | 彩色负片 | -3 / +5 | 在产 | 节点表 |
| 232 | Kodak PROFESSIONAL EKTACHROME Film E100S | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 233 | Kodak PROFESSIONAL EKTACHROME Film E100SW | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 234 | Kodak PROFESSIONAL EKTACHROME Film E100VS | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 区间内无补偿 |
| 267 | Kodak PROFESSIONAL EKTAPRESS Film PJ100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 237 | Kodak PROFESSIONAL ELITE Chrome 100 Film | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 279 | Kodak PROFESSIONAL Law Enforcement Film LE100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 246 | Kodak PROFESSIONAL PORTRA 100T Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 282 | Kodak PROFESSIONAL PRO 100T Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 272 | Kodak PROFESSIONAL PROFOTO XL 100 Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 258 | Kodak PROFESSIONAL SUPRA 100 Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 251 | Kodak PROFESSIONAL ULTRA COLOR Film 100UC | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 254 | Kodak ROYAL GOLD 100 Film | 100 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 33 | Kodak T-MAX 100 | 100 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 266 | Kodak AEROCOLOR IV Negative Film 2460 | 125 | 彩色负片 | -3.5 / +5 | 在产 | 节点表 |
| 4 | Kodak PLUS-X 125 | 125 | 黑白负片 | -4 / +7 | 停产 | 节点表 |
| 262 | Kodak VERICHROME Pan Film | 125 | 黑白负片 | -3.5 / +6 | 停产 | 节点表 |
| 228 | Kodak EKTACHROME 160T Professional Film (EPT) | 160 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 17 | Kodak EKTACOLOR PRO 160 | 160 | 彩色负片 | -4.5 / +6.5 | 在产 | 幂律 |
| 30 | Kodak PORTRA 160 | 160 | 彩色负片 | -4.5 / +6.5 | 在产 | 幂律 |
| 247 | Kodak PROFESSIONAL PORTRA 160NC Film | 160 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 248 | Kodak PROFESSIONAL PORTRA 160VC Film | 160 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 273 | Kodak PROFESSIONAL VERICOLOR III Film Type S | 160 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 7 | Kodak COLORPLUS 200 | 200 | 彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 12 | Kodak E200 | 200 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 24 | Kodak GOLD 200 | 200 | 彩色负片 | -3.5 / +5.5 | 在产 | 幂律 |
| 244 | Kodak High Definition 200 Film | 200 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 27 | Kodak KODACHROME 200 | 200 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 29 | Kodak KODACOLOR 200 | 200 | 彩色负片 | -3 / +5 | 在产 | 幂律 |
| 238 | Kodak PROFESSIONAL ELITE Chrome 200 Film | 200 | 彩色反转片 | -2.5 / +2 | 停产 | 区间内无补偿 |
| 270 | Kodak PROFESSIONAL ELITE COLOR Film 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 283 | Kodak PROFESSIONAL ROYAL SUPRA 200 Film | 200 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 255 | Kodak ROYAL GOLD 200 Film | 200 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 39 | Kodak VISION2 200T 5217/7217 | 200 | 彩色电影负片 | -4.5 / +5.5 | 停产 | 节点表 |
| 43 | Kodak VISION3 200T 5213/7213 | 200 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 1 | Kodak EASTMAN DOUBLE-X 5222/7222 | 250 | 黑白电影负片 | -4 / +7 | 在产 | 幂律 |
| 40 | Kodak VISION2 250D 5205/7205 | 250 | 彩色电影负片 | -4.5 / +5.5 | 停产 | 节点表 |
| 44 | Kodak VISION3 250D 5207/7207 | 250 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 229 | Kodak EKTACHROME 320T Professional Film (EPJ) | 320 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 36 | Kodak TRI-X 320 / 320TXP | 320 | 黑白负片 | -4 / +7.5 | 在产 | 节点表 |
| 2 | Kodak BW400CN | 400 | 黑白染料负片 | -4 / +6.5 | 停产 | 区间内无补偿 |
| 241 | Kodak EKTACHROME 400X Professional Film (EPL) | 400 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 18 | Kodak EKTACOLOR PRO 400 | 400 | 彩色负片 | -5 / +7 | 在产 | 幂律 |
| 21 | Kodak EKTAPAN 400 | 400 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 239 | Kodak ELITE Chrome 400 Film | 400 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 245 | Kodak High Definition 400 Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 31 | Kodak PORTRA 400 | 400 | 彩色负片 | -5 / +7 | 在产 | 幂律 |
| 5 | Kodak PORTRA 400BW | 400 | 黑白染料负片 | -4 / +6.5 | 停产 | 幂律 |
| 268 | Kodak PROFESSIONAL EKTAPRESS Film PJ400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 271 | Kodak PROFESSIONAL ELITE COLOR Film 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 280 | Kodak PROFESSIONAL Law Enforcement Film LE400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 249 | Kodak PROFESSIONAL PORTRA 400NC Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 250 | Kodak PROFESSIONAL PORTRA 400VC Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 284 | Kodak PROFESSIONAL ROYAL SUPRA 400 Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 259 | Kodak PROFESSIONAL SUPRA 400 Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 261 | Kodak PROFESSIONAL T400 CN Film | 400 | 黑白染料负片 | -4 / +6.5 | 停产 | 区间内无补偿 |
| 252 | Kodak PROFESSIONAL ULTRA COLOR Film 400UC | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 256 | Kodak ROYAL GOLD 400 Film | 400 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 34 | Kodak T-MAX 400 | 400 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 37 | Kodak TRI-X 400 / 400TX | 400 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 9 | Kodak ULTRA MAX 400 | 400 | 彩色负片 | -3.5 / +5 | 在产 | 幂律 |
| 41 | Kodak VISION2 500T 5218/7218 | 500 | 彩色电影负片 | -4.5 / +5.5 | 停产 | 节点表 |
| 45 | Kodak VISION3 500T 5219/7219 | 500 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 19 | Kodak EKTACOLOR PRO 800 | 800 | 彩色负片 | -4.5 / +7 | 在产 | 幂律 |
| 32 | Kodak PORTRA 800 | 800 | 彩色负片 | -4.5 / +7 | 在产 | 幂律 |
| 269 | Kodak PROFESSIONAL EKTAPRESS Film PJ800 | 800 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 281 | Kodak PROFESSIONAL Law Enforcement Film LE800 | 800 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 285 | Kodak PROFESSIONAL ROYAL SUPRA 800 Film | 800 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 260 | Kodak PROFESSIONAL SUPRA 800 Film | 800 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 227 | Kodak ULTRA MAX 800 | 800 | 彩色负片 | -3.5 / +5 | 在产 | 区间内无补偿 |
| 264 | Kodak Recording Film 2475 | 1000 | 黑白负片 | -3.5 / +6 | 停产 | 节点表 |
| 257 | Kodak ROYAL GOLD 1000 Film | 1000 | 彩色负片 | -3.5 / +5 | 停产 | 区间内无补偿 |
| 236 | Kodak EKTACHROME P1600 Professional Film (EPH) | 1600 | 彩色反转片 | -2.5 / +2 | 停产 | 幂律 |
| 22 | Kodak EKTAPAN P3200 | 3200 | 黑白负片 | -3.5 / +6 | 在产 | 节点表 |
| 35 | Kodak T-MAX P3200 | 3200 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 274 | Kodak Commercial Film 4127 | — | 黑白技术负片 | -2.5 / +4 | 停产 | 幂律 |
| 275 | Kodak Direct Positive Panchromatic Film 5246 | — | 黑白电影负片 | -3.5 / +6 | 停产 | 幂律 |
| 235 | Kodak EKTACHROME Professional Infrared EIR Film | — | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 276 | Kodak EKTAGRAPHIC HC Slide Film | — | 黑白反转片 | -2.5 / +2 | 停产 | 幂律 |
| 263 | Kodak High Speed Infrared Film HIE | — | 扩展红敏黑白负片 | -3.5 / +6.5 | 停产 | 幂律 |
| 242 | Kodak PROFESSIONAL EKTACHROME Duplicating Film EDUPE | — | 彩色反转片 | -2.5 / +2 | 停产 | 区间内无补偿 |
| 277 | Kodak PROFESSIONAL Film SO-132 | — | 黑白负片 | -3.5 / +6 | 停产 | 幂律 |
| 278 | Kodak VERICOLOR Slide Film | — | 彩色反转片 | -2.5 / +2 | 停产 | 幂律 |

### Fujifilm（40 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 46 | Fujifilm FORTIA / FORTIA SP 50 | 50 | 彩色反转片 | -2 / +1.5 | 停产 | 幂律 |
| 57 | Fujifilm VELVIA 50 | 50 | 彩色反转片 | -2.5 / +2 | 在产 | 节点表 |
| 47 | Fujifilm T64 / RTP 64T II | 64 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 48 | Fujifilm ASTIA 100F | 100 | 彩色反转片 | -2.5 / +2.5 | 停产 | 节点表 |
| 60 | Fujifilm FUJICOLOR 100 | 100 | 彩色负片 | -3 / +4.5 | 在产 | 节点表 |
| 65 | Fujifilm NEOPAN 100 ACROS | 100 | 黑白负片 | -4 / +7.5 | 停产 | 固定补偿 |
| 66 | Fujifilm NEOPAN 100 ACROS II | 100 | 黑白负片 | -4 / +7.5 | 在产 | 固定补偿 |
| 69 | Fujifilm NEOPAN SS 100 | 100 | 黑白负片 | -4 / +6.5 | 停产 | 节点表 |
| 49 | Fujifilm PROVIA 100F | 100 | 彩色反转片 | -2.5 / +2.5 | 在产 | 节点表 |
| 76 | Fujifilm REALA 100 / REALA ACE | 100 | 彩色负片 | -3.5 / +5.5 | 停产 | 节点表 |
| 52 | Fujifilm SENSIA 100 / SENSIA III 100 | 100 | 彩色反转片 | -2.5 / +2.5 | 停产 | 节点表 |
| 77 | Fujifilm SUPERIA 100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 55 | Fujifilm TREBI 100C | 100 | 彩色反转片 | -2.5 / +2.5 | 停产 | 节点表 |
| 58 | Fujifilm VELVIA 100 | 100 | 彩色反转片 | -2.5 / +2 | 在产 | 节点表 |
| 59 | Fujifilm VELVIA 100F | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 70 | Fujifilm PRO 160C / 160NC | 160 | 彩色负片 | -4 / +5.5 | 停产 | 节点表 |
| 71 | Fujifilm PRO 160NH | 160 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 72 | Fujifilm PRO 160NPC | 160 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 73 | Fujifilm PRO 160NS | 160 | 彩色负片 | -4 / +5.5 | 停产 | 固定补偿 |
| 62 | FUJIFILM 200 | 200 | 彩色负片 | -3 / +5 | 在产 | 节点表 |
| 61 | Fujifilm FUJICOLOR C200 | 200 | 彩色负片 | -3.5 / +5 | 在产 | 节点表 |
| 53 | Fujifilm SENSIA 200 | 200 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 78 | Fujifilm SUPERIA 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 63 | FUJIFILM 400 | 400 | 彩色负片 | -3.5 / +5 | 在产 | 节点表 |
| 67 | Fujifilm NEOPAN 400 PRESTO | 400 | 黑白负片 | -4 / +6.5 | 停产 | 节点表 |
| 74 | Fujifilm PRO 400H | 400 | 彩色负片 | -4.5 / +6 | 停产 | 节点表 |
| 50 | Fujifilm PROVIA 400F | 400 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 51 | Fujifilm PROVIA 400X | 400 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 54 | Fujifilm SENSIA 400 | 400 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 79 | Fujifilm SUPERIA 400 | 400 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 82 | Fujifilm SUPERIA PREMIUM 400 | 400 | 彩色负片 | -4 / +5.5 | 在产 | 节点表 |
| 83 | Fujifilm SUPERIA Venus 400 | 400 | 彩色负片 | -4 / +5.5 | 停产 | 节点表 |
| 85 | Fujifilm SUPERIA X-TRA 400 | 400 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 56 | Fujifilm TREBI 400 | 400 | 彩色反转片 | -2 / +2 | 停产 | 节点表 |
| 75 | Fujifilm PRO 800 / NPZ 800 | 800 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 80 | Fujifilm SUPERIA 800 | 800 | 彩色负片 | -4 / +4.5 | 停产 | 节点表 |
| 84 | Fujifilm SUPERIA Venus 800 | 800 | 彩色负片 | -4 / +5 | 停产 | 节点表 |
| 64 | Fujifilm NATURA 1600 | 1600 | 彩色负片 | -3.5 / +4.5 | 停产 | 节点表 |
| 68 | Fujifilm NEOPAN 1600 SUPER PRESTO | 1600 | 黑白负片 | -3.5 / +5.5 | 停产 | 节点表 |
| 81 | Fujifilm SUPERIA 1600 | 1600 | 彩色负片 | -3.5 / +4 | 停产 | 节点表 |

### ILFORD / HARMAN（18 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 100 | ILFORD / HARMAN PAN F PLUS | 50 | 黑白负片 | -3.5 / +7 | 在产 | 幂律 |
| 94 | ILFORD / HARMAN ORTHO PLUS 80 | 80 | 正色黑白负片 | -3.5 / +6.5 | 在产 | 幂律 |
| 86 | ILFORD / HARMAN DELTA 100 PROFESSIONAL | 100 | 黑白负片 | -4 / +7 | 在产 | 幂律 |
| 101 | ILFORD / HARMAN KENTMERE PAN 100 | 100 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 96 | ILFORD PAN 100 | 100 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 91 | HARMAN RED 125 | 125 | 红调彩色负片 | -2.5 / +3 | 在产 | 幂律 |
| 92 | HARMAN SWITCH AZURE 125 | 125 | 创意彩色负片 | -2.5 / +3 | 在产 | 幂律 |
| 98 | ILFORD / HARMAN FP4 PLUS | 125 | 黑白负片 | -4 / +7.5 | 在产 | 幂律 |
| 89 | HARMAN Phoenix 200（第一代） | 200 | 创意彩色负片 | -2.5 / +3.5 | 停产 | 幂律 |
| 90 | HARMAN Phoenix II 200 | 200 | 创意彩色负片 | -3 / +4 | 在产 | 幂律 |
| 102 | ILFORD / HARMAN KENTMERE PAN 200 | 200 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 95 | ILFORD / HARMAN SFX 200 | 200 | 扩展红敏黑白负片 | -4 / +6.5 | 在产 | 幂律 |
| 87 | ILFORD / HARMAN DELTA 400 PROFESSIONAL | 400 | 黑白负片 | -4 / +7 | 在产 | 幂律 |
| 99 | ILFORD / HARMAN HP5 PLUS | 400 | 黑白负片 | -4.5 / +7.5 | 在产 | 幂律 |
| 103 | ILFORD / HARMAN KENTMERE PAN 400 | 400 | 黑白负片 | -4 / +6 | 在产 | 幂律 |
| 93 | ILFORD / HARMAN XP2 SUPER 400 | 400 | 黑白染料负片 | -4.5 / +7 | 在产 | 幂律 |
| 97 | ILFORD PAN 400 | 400 | 黑白负片 | -4 / +6 | 在产 | 幂律 |
| 88 | ILFORD / HARMAN DELTA 3200 PROFESSIONAL | 3200 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |

### Konica（18 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 215 | Konica INFRARED 750 | 32 | 扩展红敏黑白负片 | -3.5 / +6.5 | 停产 | 幂律 |
| 214 | Konica IMPRESA 50 | 50 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 206 | Konica CENTURIA SUPER 100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 211 | Konica CHROME CENTURIA 100 SRA | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 213 | Konica CHROME R-100 | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 217 | Konica VX 100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 220 | Konica VX SUPER 100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 216 | Konica PROFESSIONAL 160 | 160 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 207 | Konica CENTURIA SUPER 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 212 | Konica CHROME CENTURIA 200 | 200 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 218 | Konica VX 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 221 | Konica VX SUPER 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 205 | Konica CENTURIA PROFESSIONAL 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 208 | Konica CENTURIA SUPER 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 219 | Konica VX 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 222 | Konica VX SUPER 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 209 | Konica CENTURIA SUPER 800 | 800 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 210 | Konica CENTURIA SUPER 1600 | 1600 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |

### Lomography（17 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 121 | Lomography Fantôme Kino 8 | 8 | 黑白电影负片分装 | -2.5 / +5 | 在产 | 幂律 |
| 118 | Lomography Babylon Kino 13 | 13 | 黑白电影负片分装 | -3 / +5.5 | 在产 | 幂律 |
| 124 | Lomography Color Negative 100 | 100 | 彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 120 | Lomography Earl Grey 100 | 100 | 黑白负片 | -3.5 / +6 | 在产 | 节点表 |
| 123 | Lomography Potsdam Kino 100 | 100 | 黑白电影负片分装 | -3.5 / +6.5 | 在产 | 幂律 |
| 133 | Lomography Redscale XR 50–200 | 100 | 红调彩色负片 | -2.5 / +3.5 | 在产 | 幂律 |
| 127 | Lomography LomoChrome Classicolor 200 | 200 | 创意彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 130 | Lomography LomoChrome Metropolis 2021 | 200 | 创意彩色负片 | -3 / +4 | 在产 | 幂律 |
| 131 | Lomography LomoChrome Purple 2021 | 200 | 创意彩色负片 | -3 / +4 | 在产 | 幂律 |
| 132 | Lomography LomoChrome Turquoise 2021 | 200 | 创意彩色负片 | -3 / +4 | 在产 | 幂律 |
| 134 | Lomography X-Pro Slide 200 | 200 | 彩色反转片 | -2 / +2 | 停产 | 幂律 |
| 119 | Lomography Berlin Kino 400 | 400 | 黑白电影负片分装 | -4 / +6.5 | 在产 | 幂律 |
| 125 | Lomography Color Negative 400 | 400 | 彩色负片 | -3.5 / +5 | 在产 | 幂律 |
| 122 | Lomography Lady Grey 400 | 400 | 黑白负片 | -4 / +6 | 在产 | 节点表 |
| 128 | Lomography LomoChrome Color ’92 400 | 400 | 创意彩色负片 | -3 / +4 | 在产 | 幂律 |
| 129 | Lomography LomoChrome Color ’92 Sun-kissed 400 | 400 | 创意彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 126 | Lomography Color Negative 800 | 800 | 彩色负片 | -3.5 / +5 | 在产 | 幂律 |

### Rollei（12 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 149 | ROLLEI ORTHO 25 PLUS | 25 | 正色黑白负片 | -3 / +6 | 在产 | 节点表 |
| 152 | ROLLEI RPX 25 | 25 | 黑白负片 | -3.5 / +6.5 | 在产 | 节点表 |
| 155 | ROLLEI ATP 1.1 | 32 | 高解析黑白技术片 | -2.5 / +4 | 停产 | 幂律 |
| 156 | ROLLEI BLACKBIRD 64 | 64 | 黑白负片 | -3 / +5.5 | 停产 | 幂律 |
| 150 | ROLLEI RETRO 80S | 80 | 扩展红敏黑白负片 | -3.5 / +7 | 在产 | 节点表 |
| 153 | ROLLEI RPX 100 | 100 | 黑白负片 | -3.5 / +6.5 | 在产 | 节点表 |
| 157 | ROLLEI CN200 | 200 | 彩色负片 | -3 / +4.5 | 停产 | 幂律 |
| 158 | ROLLEI CROSSBIRD 200 | 200 | 彩色反转/交叉冲洗片 | -2 / +2 | 停产 | 节点表 |
| 159 | ROLLEI SUPERPAN 200 | 200 | 扩展红敏黑白负片 | -4 / +6.5 | 在产 | 节点表 |
| 148 | ROLLEI INFRARED 400 | 400 | 扩展红敏黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 151 | ROLLEI RETRO 400S | 400 | 扩展红敏黑白负片 | -4 / +6.5 | 在产 | 幂律 |
| 154 | ROLLEI RPX 400 | 400 | 黑白负片 | -4 / +6 | 在产 | 节点表 |

### Agfa（11 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 202 | Agfa AGFACHROME RSX II 50 | 50 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 201 | Agfa AGFACOLOR ULTRA 50 | 50 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 194 | Agfa AVIPHOT PAN 80 | 80 | 扩展红敏黑白负片 | -3.5 / +6.5 | 停产 | 幂律 |
| 203 | Agfa AGFACHROME RSX II 100 | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 197 | Agfa AGFACOLOR OPTIMA II 100 | 100 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 200 | Agfa AGFACOLOR PORTRAIT XPS 160 | 160 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 204 | Agfa AGFACHROME RSX II 200 | 200 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 198 | Agfa AGFACOLOR OPTIMA II 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 195 | Agfa AVIPHOT PAN 200 | 200 | 扩展红敏黑白负片 | -3.5 / +6.5 | 停产 | 幂律 |
| 199 | Agfa AGFACOLOR OPTIMA II 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 196 | Agfa AVIPHOT PAN 400S | 400 | 扩展红敏黑白负片 | -3.5 / +6.5 | 停产 | 幂律 |

### AgfaPhoto（10 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 168 | AgfaPhoto AGFAPAN APX 25 | 25 | 黑白负片 | -3.5 / +7 | 停产 | 节点表 |
| 169 | AgfaPhoto AGFAPAN APX 100 | 100 | 黑白负片 | -4 / +7 | 停产 | 节点表 |
| 172 | AgfaPhoto APX 100 NEW | 100 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 171 | AgfaPhoto CT PRECISA 100 | 100 | 彩色反转片 | -2.5 / +2 | 停产 | 节点表 |
| 174 | AgfaPhoto VISTA 100 | 100 | 彩色负片 | -3 / +4.5 | 停产 | 节点表 |
| 167 | AgfaPhoto AGFA SCALA 200X | 200 | 黑白反转片 | -2.5 / +2 | 停产 | 节点表 |
| 175 | AgfaPhoto VISTA 200 / VISTA PLUS 200 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |
| 170 | AgfaPhoto AGFAPAN APX 400 | 400 | 黑白负片 | -4 / +6.5 | 停产 | 节点表 |
| 173 | AgfaPhoto APX 400 NEW | 400 | 黑白负片 | -4 / +6 | 在产 | 幂律 |
| 176 | AgfaPhoto VISTA 400 / VISTA PLUS 400 | 400 | 彩色负片 | -3.5 / +5 | 停产 | 节点表 |

### FOMA（9 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 108 | FOMA FOMAPAN 100 CLASSIC | 100 | 黑白负片 | -4 / +6.5 | 在产 | 节点表 |
| 104 | FOMA FOMAPAN CINE 100 | 100 | 黑白电影负片 | -4 / +6.5 | 在产 | 节点表 |
| 111 | FOMA FOMAPAN R 100 | 100 | 黑白反转片 | -2.5 / +2 | 在产 | 节点表 |
| 109 | FOMA FOMAPAN 200 CREATIVE | 200 | 黑白负片 | -3.5 / +6 | 在产 | 节点表 |
| 112 | FOMA RETROPAN 320 SOFT | 320 | 黑白负片 | -3.5 / +7 | 在产 | 节点表 |
| 106 | FOMA CINE ORTHO 400 | 400 | 正色黑白电影负片 | -3 / +5.5 | 在产 | 节点表 |
| 110 | FOMA FOMAPAN 400 ACTION | 400 | 黑白负片 | -3.5 / +5.5 | 在产 | 节点表 |
| 105 | FOMA FOMAPAN CINE 400 | 400 | 黑白电影负片 | -3.5 / +5.5 | 在产 | 节点表 |
| 107 | FOMA ORTHO 400 | 400 | 正色黑白负片 | -3 / +5.5 | 在产 | 节点表 |

### ADOX（8 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 141 | ADOX CMS 20 II | 20 | 超高解析黑白负片 | -2.5 / +4.5 | 在产 | 节点表 |
| 142 | ADOX HR-50 | 50 | 扩展红敏黑白负片 | -3.5 / +7 | 在产 | 幂律 |
| 146 | ADOX SCALA 50 | 50 | 黑白反转片 | -2.5 / +2.5 | 在产 | 幂律 |
| 140 | ADOX CHS 100 II | 100 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 144 | ADOX COLOR IMPLOSION 100 | 100 | 创意彩色负片 | -2.5 / +3 | 停产 | 幂律 |
| 143 | ADOX SILVERMAX 100 | 100 | 黑白负片 | -4 / +7 | 在产 | 节点表 |
| 147 | ADOX SCALA 160 | 160 | 黑白反转片 | -2.5 / +2 | 在产 | 节点表 |
| 145 | ADOX COLOR MISSION 200 | 200 | 彩色负片 | -3 / +4 | 停产 | 幂律 |

### 乐凯 / LUCKY（7 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 163 | 乐凯 / LUCKY COLOR 100 | 100 | 彩色负片 | -3 / +4 | 停产 | 幂律 |
| 165 | 乐凯 / LUCKY SHD 100 | 100 | 黑白负片 | -3.5 / +6 | 在产 | 幂律 |
| 162 | 乐凯 / LUCKY C200 | 200 | 彩色负片 | -3 / +4.5 | 在产 | 幂律 |
| 164 | 乐凯 / LUCKY COLOR 200 | 200 | 彩色负片 | -3 / +4 | 停产 | 幂律 |
| 224 | 乐凯 / LUCKY 金 BR200 彩色胶卷 | 200 | 彩色负片 | -3.5 / +5 | 停产 | 幂律 |
| 166 | 乐凯 / LUCKY SHD 400 | 400 | 黑白负片 | -3 / +5.5 | 在产 | 幂律 |
| 223 | 乐凯 / LUCKY 日光型彩色反转片 | — | 彩色反转片 | -2.5 / +2 | 停产 | 幂律 |

### FILM Ferrania（6 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 178 | FERRANIA ORTO 50 | 50 | 正色黑白负片 | -3 / +5 | 在产 | 幂律 |
| 179 | FERRANIA P30 80 | 80 | 黑白电影负片 | -3 / +5.5 | 在产 | 节点表 |
| 181 | FILM Ferrania SOLARIS FG PLUS 100 | 100 | 彩色负片 | -3 / +4 | 停产 | 幂律 |
| 180 | FERRANIA P33 160 | 160 | 黑白负片 | -3.5 / +5.5 | 在产 | 幂律 |
| 182 | FILM Ferrania SOLARIS FG PLUS 200 | 200 | 彩色负片 | -3 / +4.5 | 停产 | 幂律 |
| 183 | FILM Ferrania SOLARIS FG PLUS 400 | 400 | 彩色负片 | -3 / +4 | 停产 | 幂律 |

### CineStill（5 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 113 | CineStill 50Daylight 50D | 50 | 彩色电影负片改制 | -5 / +6 | 在产 | 幂律 |
| 116 | CineStill REDRUM 200 | 200 | 红调彩色负片 | -2.5 / +3.5 | 在产 | 幂律 |
| 117 | CineStill BwXX | 250 | 黑白电影负片分装 | -4 / +7 | 在产 | 幂律 |
| 114 | CineStill 400Dynamic / 400D | 400 | 彩色电影负片改制 | -4.5 / +6 | 在产 | 幂律 |
| 115 | CineStill 800Tungsten / 800T | 800 | 彩色电影负片改制 | -4.5 / +6 | 在产 | 幂律 |

### ORWO（5 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 136 | ORWO UN54 | 100 | 黑白电影负片 | -3.5 / +6.5 | 在产 | 幂律 |
| 137 | ORWO WOLFEN NP100 | 100 | 黑白负片 | -3.5 / +6.5 | 在产 | 幂律 |
| 135 | ORWO N74 PLUS | 400 | 黑白电影负片 | -4 / +6 | 在产 | 幂律 |
| 138 | ORWO WOLFEN NC400 | 400 | 彩色电影负片 | -3.5 / +5 | 在产 | 幂律 |
| 139 | ORWO WOLFEN NC500 | 500 | 彩色电影负片 | -3.5 / +4.5 | 在产 | 幂律 |

### Reflx Lab（5 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 187 | Reflx Lab 100D | 100 | 彩色电影负片 | -5 / +6 | 在产 | 节点表 |
| 185 | Reflx Lab PRO100 | 100 | 彩色负片 | -3 / +5 | 在产 | 节点表 |
| 188 | Reflx Lab 250D | 250 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 189 | Reflx Lab 500T | 500 | 彩色电影负片 | -5.5 / +6.5 | 在产 | 节点表 |
| 186 | Reflx Lab 800T | 800 | 彩色电影负片改制 | -4.5 / +6 | 在产 | 幂律 |

### SILBERSALZ35（4 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 190 | SILBERSALZ35 050D | 50 | 彩色电影负片分装 | -5.5 / +6.5 | 在产 | 节点表 |
| 191 | SILBERSALZ35 200T | 200 | 彩色电影负片分装 | -5.5 / +6.5 | 在产 | 节点表 |
| 192 | SILBERSALZ35 250D | 250 | 彩色电影负片分装 | -5.5 / +6.5 | 在产 | 节点表 |
| 193 | SILBERSALZ35 500T | 500 | 彩色电影负片分装 | -5.5 / +6.5 | 在产 | 节点表 |

### 上海 / SHANGHAI（2 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 160 | 上海 / SHANGHAI GP3 100 | 100 | 黑白负片 | -3.5 / +6 | 在产 | 节点表 |
| 161 | 上海 / SHANGHAI GP3 400 | 400 | 黑白负片 | -3.5 / +5.5 | 在产 | 幂律 |

### BERGGER（1 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 177 | BERGGER PANCRO 400 | 400 | 黑白负片 | -4 / +7 | 在产 | 节点表 |

### Japan Camera Hunter（1 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 184 | Japan Camera Hunter JCH STREETPAN 400 | 400 | 扩展红敏黑白负片 | -3.5 / +6 | 在产 | 幂律 |

### 代代红 / DAIDAIHONG（1 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 226 | 代代红 / DAIDAIHONG 1112 型黑白电影底片 | — | 黑白电影负片 | -3.5 / +6 | 停产 | 幂律 |

### 太行 / TAIHANG（1 款）

| ID | 胶片全名 | ISO | 类型 | 宽容度 EV（阴影/高光） | 状态 | 倒易率 |
| ---: | --- | ---: | --- | :---: | :---: | --- |
| 225 | 太行 / TAIHANG 1212 型彩色底片 | — | 彩色电影负片 | -4.5 / +5.5 | 停产 | 幂律 |

## 四、按胶片类型统计

| 类型 | 数量 |
| --- | ---: |
| 彩色负片 | 103 |
| 黑白负片 | 53 |
| 彩色反转片 | 48 |
| 彩色电影负片 | 14 |
| 扩展红敏黑白负片 | 12 |
| 创意彩色负片 | 10 |
| 黑白电影负片 | 8 |
| 黑白反转片 | 5 |
| 黑白电影负片分装 | 5 |
| 彩色电影负片改制 | 4 |
| 正色黑白负片 | 4 |
| 黑白染料负片 | 4 |
| 彩色电影负片分装 | 4 |
| 红调彩色负片 | 3 |
| 黑白技术负片 | 3 |
| 超高解析黑白负片 | 1 |
| 正色黑白电影负片 | 1 |
| 彩色电影反转片 | 1 |
| 高解析黑白技术片 | 1 |
| 彩色反转/交叉冲洗片 | 1 |
| **合计** | **285** |

## 五、附注

1. **全名去重规则**：若数据库 `model` 字段已以厂商名或其品牌词开头，则全名直接采用 `model`，避免出现「Rollei ROLLEI RPX 100」「Reflx Lab Reflx Lab PRO100」这类重复。
2. **名称修订**：基础库中 9 款胶片的型号已按 `profilePatches` 更新，例如 ID 13 更新为 `EKTACHROME 100D Color Reversal Film 5294/7294`，ID 15/16 补充了厂家后缀。
3. **宽容度含义**：`shadowEv` / `highlightEv` 是相对 18% 灰的曝光偏移；本清单直接展示数据库原值，未叠加用户在应用内保存的自定义覆盖值。
4. **倒易率数据**：清单展示的是胶片默认引用的方法类型；应用内用户自定义倒易率覆盖不会改变本清单。
5. **统计口径**：部分早期/资料型胶片没有 ISO 数值（共 11 款），表中以「—」表示；类型字段保留数据库原文，故存在「彩色电影负片改制」「黑白电影负片分装」等细分类型。

