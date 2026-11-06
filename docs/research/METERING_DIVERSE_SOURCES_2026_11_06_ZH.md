# 测光训练的多题材图源调查

调查日期：2026-11-06。任务：在现有训练继续期间寻找多题材图源，核对来源和许可证。候选独立保存，不改正在运行的400张冻结清单，不开启第二个采集器，不下载图片或写入D盘登记簿。

## 本次结果与证据边界

通过Wikimedia Commons官方API检索八类题材；共得到111个唯一候选条目，逐条核对API声明的CC BY 4.0、JPEG/PNG原文件、8–64MP、单文件≤80MiB，以及作者、许可链接、原文件URL、文件版本时间和远端SHA1。排除旧664张清单中的相同远端SHA1。

这些是**许可和尺寸元数据初筛候选**，不是111张已完成质量验收的独立摄影场景。检索关键词不是题材真值；尚未下载像素，未完成原图解码、SHA256/远端SHA1比对、phash全历史去重、同场景分组、水印/拼接/强处理/生成图筛查或人物相关权利检查。条目均标记未准入，不能直接作为ready训练数据。

机器可读记录：同目录 `METERING_DIVERSE_SOURCE_CANDIDATES_2026_11_06.json`。完整API响应、逐条许可元数据及一次性研究脚本：当前工作区 `outputs/source-research-20261106/`。许可声明仅反映本次查询状态；正式采集还需冻结来源页修订版本与许可证据。

## 图源选择

1. **首选Commons中明确CC0/CC BY 4.0的摄影作品**。兼容现有图源规范，能够逐图保存作者、作品页、原文件和版本证据。本次候选全部为CC BY 4.0，不把网站整体许可当作图片许可。[Commons许可类别](https://commons.wikimedia.org/wiki/Category:CC-BY-4.0)。CC BY 4.0允许商业用途及改编，使用/分发相关素材须保留适当署名、许可链接及修改说明；不能仅用项目代码的Apache-2.0覆盖图片许可。[CC官方条款](https://creativecommons.org/licenses/by/4.0/)
2. **保留既有LIU4K图源作基础对照**。新题材用于补齐曝光条件和场景分布，不靠增加同景近似图扩大有效样本量。来源以现有作者清单与原文件散列为准，不从第三方重编码镜像替换。
3. **Smithsonian Open Access仅作后续补充候选平台**。明确CC0的资产可用于商业用途，但不是每个藏品页面的图片都带CC0。可关注动物、物件和环境摄影；绘画扫描、标本白底图及历史照片不作为自然场景测光主训练集。该平台尚不在本项目既有采集白名单中，不能自动接入。[官方FAQ](https://www.si.edu/openaccess/faq)
4. **Open Images暂不接入本轮**。官方区分标注CC BY 4.0与图片CC BY 2.0，并要求使用者自行核实逐图许可。虽然类别丰富，但不能用标注许可替代图像许可，且需要找回原尺寸、原作者和来源证据；CC BY 2.0也不在当前白名单中。[官方许可段落](https://storage.googleapis.com/openimages/web/factsfigures_v7.html#licenses)
5. **Flickr暂作索引，不能直接开启采集**。其API有CC0和CC BY 4.0选项，但API使用条款另行适用，商业使用需事先安排；不能仅依据单张照片的CC标签忽略获取方式。[许可枚举](https://www.flickr.com/services/api/flickr.photos.licenses.getInfo.html)、[API说明](https://www.flickr.com/services/api/)
6. **不将Unsplash、Pexels作为当前自动训练图源**。Unsplash官方说明对机器学习/AI用途有限制；Pexels条款明确限制未经授权的机器学习数据提取及系统性批量复制。免费浏览、下载不等于获得自动训练采集许可。[Unsplash说明](https://help.unsplash.com/en/articles/2612332-what-do-you-mean-by-compiling-images-to-replicate-a-similar-or-competing-service)、[Pexels条款第8节](https://www.pexels.com/terms-of-service/)

## 接入规范

- 继续使用现有LIU4K/Commons白名单、唯一采集器、共享登记簿和自动准入流程；CC0、CC BY 4.0逐图匹配。NC、ND、SA或许可不明项不进入当前白名单；这是项目范围选择，不宣称SA一概禁止训练。下载时再次核对原作者/源站记录及许可证据。
- 仅获取原文件；不放大、不重编码、不用缩略图替代原图。保存来源页、页面修订号、作者、许可及链接、下载URL、文件版本、远端SHA1、本地SHA256、原尺寸、字节数、拍摄/相机元数据（如存在）、获取时间及修改记录。
- 下载后核对可解码性、8–64MP、80MiB和色彩/格式支持；继续执行全历史SHA256/phash去重。相机/作者三秒内连拍和同景裁切归组，无法用EXIF排除同景的仍需按已有分组规则处理，不能据“不同文件名”判独立。
- 固定val/test以及任何已有训练引用保持原身份和分割；候选是否曾被其他任务使用仍待全登记簿核验。未来验证集按场景、作者/设备分离，不从已训练图片改名产生“新盲测”。
- 质量上优先真实自然摄影，排除生成图、拼图、明显水印、HDR/局部强色调映射和截图；保留有意的夜景、逆光和高调画面。元数据自动检查不是逐图视觉验收，也不保证发现全部生成或后期处理。
- 本轮仍使用冻结400张。候选通过准入后进入下一版冻结清单；比较数据扩充效果时三模型共用新清单并明确与本轮区分。
- 采集继续受整个D盘共享图池12GiB限制，容量不足暂停下载、训练继续；仅在已授权缓存区写入，所有消费者与读取租约/真实断点回执检查通过后才能回收专属副本。元数据发现过程不占用图片缓存，也不授予额外删除权限。

## 训练价值和下一版采样建议

题材之外，另标记明暗背景、主体占比、光源大小、逆光、混合照明和有意低调/高调。人物图片仅用于曝光场景覆盖，不引入人脸识别或身份标签。

建议下一版以400个**通过准入的独立场景**为预算，八类各约50个，并在每类内部均衡光照条件；当前111个检索条目尚未达到此配额。按作者/设备和场景限制集中度，避免室内样本主要来自同一教堂、人物样本主要来自同一摄影棚。图源质量不足时保留缺额，不靠同景连拍凑数。

原图曝光仍只是弱监督。对有意的暗夜、剪影、雪景统一设为参考曝光0EV，会保留原作者的曝光偏好；扩充这类数据时应记录摄影意图并补充真实包围曝光/RAW-YUV配对验证，不能把合成曝光误差降低直接解释为手机绝对测光准确度提高。

## 本次检索分布与示例

下表数量是各检索主题下通过许可/尺寸初筛的条目数；主题未作像素验证，示例仅用于追溯来源。

| 检索题材 | 初筛条目 | 示例原图来源 | 原尺寸 |
|---|---:|---|---|
| 夜景街道 | 20 | [2024-10-23 A street in Yamanochi at night 1.jpg](https://commons.wikimedia.org/wiki/File:2024-10-23_A_street_in_Yamanochi_at_night_1.jpg) | 5370×3580 |
| 室内窗光 | 19 | [Interior view of a window in the prayer house at Sirotski Dom complex in Gorelovka, Georgia.jpg](https://commons.wikimedia.org/wiki/File:Interior_view_of_a_window_in_the_prayer_house_at_Sirotski_Dom_complex_in_Gorelovka,_Georgia.jpg) | 3072×4080 |
| 逆光剪影 | 13 | [ALMA antenna at OSF backlit by the Sun (ib-alma08).jpg](https://commons.wikimedia.org/wiki/File:ALMA_antenna_at_OSF_backlit_by_the_Sun_(ib-alma08).jpg) | 2440×3516 |
| 雪景亮背景 | 16 | [A snow-covered Paranal landscape (DSC08962-cc).jpg](https://commons.wikimedia.org/wiki/File:A_snow-covered_Paranal_landscape_(DSC08962-cc).jpg) | 3936×2624 |
| 人物肖像 | 6 | [Halmeoni and Amber portrait at WikiPortraits studio at Sundance 2025.jpg](https://commons.wikimedia.org/wiki/File:Halmeoni_and_Amber_portrait_at_WikiPortraits_studio_at_Sundance_2025.jpg) | 6720×4480 |
| 动物 | 9 | [Bd Bird-lover.png](https://commons.wikimedia.org/wiki/File:Bd_Bird-lover.png) | 3076×4614 |
| 食物静物 | 17 | [20251210 Halibut dish in Moscow (33630).jpg](https://commons.wikimedia.org/wiki/File:20251210_Halibut_dish_in_Moscow_(33630).jpg) | 4000×2252 |
| 日常物件 | 11 | [Basement kitchen at Biltmore Estate in Asheville, North Carolina 04.jpg](https://commons.wikimedia.org/wiki/File:Basement_kitchen_at_Biltmore_Estate_in_Asheville,_North_Carolina_04.jpg) | 5712×4284 |

合计111个唯一条目；全部声明CC BY 4.0。作者字段缺失0个，API相机型号未提供14个；均保留待核验标记。
