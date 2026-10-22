# 第三方歌词源（多源并行，逐字优先）

### 背景（实测证据）

《新世界的天空》- 鸣潮先约电台 & 飞行雪绒 这类歌，Apple 官方歌词实体仍在，但 TTML 里 **没有任何带时间的 `<p>`**：

```
Attributes.getTtmlLocalizations -> len=4102 timed=false
TTML#n head=<tt ... itunes:timing="None" xml:lang="en">...
TTML parsed paragraphs=0 first=-
```

于是 Apple Music 播放器只显示静态歌词、状态栏歌词也拿不到任何行。模块的解法与 SPW-Lyrics 插件一致：
**多源并行搜索 → 逐字（karaoke）优先 → 取词解析成时间轴 → 生成 Apple 格式 TTML → 回灌 AM 自己的 TTML 管线**，
这样播放器歌词页（逐字高亮）和 Flyme 状态栏歌词都“像官方歌词一样”滚动。

### 开关

AM 设置 → **显示选项** → **「第三方歌词源」**（副标题「官方没有滚动歌词时，用第三方词库补全（AMLL/QQ/酷狗/网易云，逐字优先）」，默认关）。
状态落在 AM 的 `shared_prefs/amflymelyric.xml`，键 `third_party_lyrics`；与「状态栏歌词」开关同一套注入方式（见 §6 第 9 条）。

### 四个源（并行，全部在 `LyricSources.java`）

| 源 | 优先级 | 搜索 | 取词 | 质量 |
| --- | --- | --- | --- | --- |
| **AMLL TTML DB** | 0 | `cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl`（4.1 MB / 2745 条，回退 raw.githubusercontent） | `<RAW_BASE>/<id>.ttml` | 逐字（字符级 `<span>`） |
| **QQ 音乐** | 1 | `POST u.y.qq.com/cgi-bin/musicu.fcg`（`DoSearchForQQMusicDesktop`），回退 `c.y.qq.com/soso/fcgi-bin/client_search_cp` | `c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg?version=15&miniversion=82&lrctype=4&musicid=<id>` → hex → QQ 变体 DES（`QqDes.java`）→ zlib | 逐字（QRC） |
| **酷狗** | 2 | `songsearch.kugou.com/song_search_v2` | `lyrics.kugou.com/search`（keyword=歌手、歌名）→ `lyrics.kugou.com/download?fmt=krc` → base64 → 4 字节 `krc1` 头 + 16 字节 XOR → zlib | 逐字（KRC） |
| **网易云** | 3 | `music.163.com/api/cloudsearch/pc` | `api/song/lyric`（`yrc` 优先、`lrc` 回退） | 逐字（YRC）/逐行 |

**选择策略**（`ThirdPartyLyrics.resolve`，对应 SPW-Lyrics 的 `LyricsSelectionPolicy`）：
1. 四个源同时发请求（各自超时 5 s 连接 / 7 s 读取，总预算 9 s）；
2. 排序 = **时间粒度优先**（逐字 > 逐行 > 纯文本）→ 同质量按源优先级 → 再按匹配分；
3. 一旦出现逐字结果，还会再等 **1200 ms** 让更高优先级源的结果进来（避免酷狗抢先压过 QQ/AMLL），然后定胜者；
4. 结果落盘 `files/amlyric-third/<playbackStoreId>.ttml`（下次秒开），AMLL 索引另存 `amll_index.jsonl` + 压缩版 `amll_index.txt`（4 MB → 1.4 MB，2745 条解析 40 ms）。

### 匹配与清洗

- 先用 `ThirdPartyLyrics.score`（标题归一化/包含/相似 + 歌手分词 + 时长差 + 版本关键词罚分）排序候选；
- 取词前用 `plausibleMatch` 守卫（标题相等/包含/相似 ≥0.72，或 **歌手完全一致 + 时长差 ≤3 s** 才允许“翻译标题”如 `Brand New Sky` ↔ `新世界的天空`）；
- 取词后再用 `plausible` 二次守卫，**拦住“同名不同曲/错歌”**（实测 AMLL 曾把《新世界的天空》匹配成《セカイ》，被这道守卫拒掉）；
- 去掉开头 30 s 内的制作人员行（出品/演唱/词曲/编曲/录音/混音/母带/监制/吉他/鼓/… 以及 `头衔：值` 格式）和**标题卡**（`歌名 - 歌手`）；
- 逐字源的分段文本按需补空格（QQ/KRC 的英文单词会被拆开，`appendWordText` 会还原成 `The sign is lining up`）；
- 标题含 instrumental / karaoke / 伴奏 / 纯音乐 / off vocal 的曲目直接跳过；同曲失败一次后本次进程不再重试。

### 注入点（4 处，互为兜底）

| 注入点 | 条件 | 作用 |
| --- | --- | --- |
| `Attributes.getTtmlLocalizations()` / `getTtml()` | 官方值为 null，或没有带时间的 `<p>` | 直接返回我们生成的 TTML |
| `TTMLParserNative.songInfoFromTTML(String)` | 入参没有带时间的 `<p>` | `chain.proceed(new Object[]{ourTtml})` 换掉解析输入，同时喂给模块自己的状态栏解析 |
| `PlayerLyricsViewModel.loadLyrics(PlaybackItem)` | 官方结果为空/无时间轴 | 给 item 设 `hasLyrics`/`hasTimeSyncedLyrics`/`offlineLyricsFilePath`，把自建 `SongInfoPtr`（`kotlin.Pair`）post 进 `mLyricsResult` + `buildTimeRangeToLyricsMap` |
| 状态栏 ticker | — | 复用 tick()：TTML 段落即逐行时间轴，换行重发通知 |

生成 TTML 前会用 **AM 自己的原生解析器预校验**（`parseTtmlPtr`）：逐字 TTML 被拒时自动回退逐行版本再试，仍失败则丢弃并重取。

### 实测（本机，v12）

```
# 《新世界的天空》：四源并行，QQ 逐字胜出，AMLL 错匹配被守住
third-party source netease -> netease:3395393730 "Brand New Sky (新世界的天空)" lines=57 quality=line
third-party source kugou  -> kugou:674137675 "Brand New Sky" lines=57 quality=karaoke
third-party source qq     -> qq:689275632 "Brand New Sky (新世界的天空)" lines=57 quality=karaoke
third-party source amll   -> implausible match, rejected: amll:0 "セカイ (feat. …)"
third-party: winner qq:689275632 … quality=karaoke score=0.45
third-party lyrics ready (… quality=karaoke) lines=57

# 逐字源证据（设备端 harness 直跑 resolve）
# 《祝福》YOASOBI：AMLL 胜出，49 行 484 词
third-party amll candidate id=1645308331 title=祝福 artist=YOASOBI titleSim=1.00 artistSim=1.00
third-party amll ttml fetched … bytes=35292 lines=49
third-party source amll -> amll:0 "祝福" / YOASOBI lines=49 quality=karaoke score=1.00
== line[0] 917..3669 : 遥か遠くに浮かぶ星を

# 《晴天》周杰伦：QQ 逐字 504 词
third-party source qq -> qq:97773 "晴天" / 周杰伦 lines=51 quality=karaoke score=1.00
```

截图：`build/v12_lyrics.png`（播放器歌词页 + 状态栏同步）、`build/am_set4.png`（两个开关）。
测试工具：`build/devtest/`（设备端 `app_process` harness，可直接 resolve 任意歌名/歌手并打印四源结果与逐字数）。

### 限制

- 网络端点均为各平台网页/公开接口，随时可能变；AMLL 走 jsDelivr 镜像，首次需下载 4.1 MB 索引。
- 逐字高亮由 AM 原生 TTML 渲染，逐字 TTML 若被判非法会退回逐行（日志 `falling back to line-level`）。
- 不合并翻译/音译；制作人员与标题卡只按启发式过滤开头部分。
- 匹配是打分 + 双守卫，冷门歌可能四个源都没有 → 不显示歌词（不会乱贴别人的歌词）。
- 官方带时间轴歌词时完全不介入（`sOfficialTimed` 守卫）。
