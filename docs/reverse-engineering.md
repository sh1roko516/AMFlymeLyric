# Apple Music × Flyme 状态栏歌词：逆向与实现笔记

## 一、Flyme 状态栏歌词的实现形式（逆向结论）

Flyme 的状态栏歌词**不是独立 API**，而是复用 Android 媒体通知的 `tickerText`。SystemUI 在收到通知更新时做四项判定，全部满足才把 `tickerText` 放进状态栏左侧的跑马灯 `AdvertTickerView`：

| 条件 | 判定来源 |
| --- | --- |
| `(notification.flags & 0x01000000) != 0` | `FLAG_ALWAYS_SHOW_TICKER = 16777216` |
| `notification.tickerText != null` | 即当前歌词行 |
| `!statusBarNotification.isClearable()` | `(flags & 0x2) == 0 && (flags & 0x20) == 0 && !isLive()` |
| 主媒体会话处于播放态 | SystemUI `NotificationMediaManager.isPlayingState(state)` |

另外通知要带 `FLAG_ONLY_UPDATE_TICKER = 0x02000000`，SystemUI 才会走「持续更新」路径而不是一次性 tick。

关键源码（反编译自 `SystemUI.apk` / `framework.jar`，见 `out/systemui/sources`、`out/fw_Notification.java`）：

- `com/flyme/systemui/statusbar/ticker/AdvertTickerView.java`：
  `boolean z = ((notification.flags & 16777216) == 0 || notification.tickerText == null || statusBarNotification.isClearable() || !this.mIsPlayingState) ? false : true;`
  满足后 `mTextSwitcher.setText(notification.tickerText)` → `((MarqueeTextView) mTextSwitcher.getChildAt(displayedChild)).startScrollSoon()`；同时用 `mCurrentKey` 锁定通知 key，其它 key 的更新被忽略（不会闪烁）。
- `com/flyme/systemui/statusbar/ticker/NotificationTickController.java:304-314`：`addNotification` 成功后调用 `liveNotificationController.getLyricsNotifications(tickerText, tickerDrawable, entry)`，并且 `if ((flags & 33554432) != 0) return true;`。
- `com/flyme/statusbar/livenotification/LiveNotificationController.java:1170`：把歌词同样投给 Flyme「实况通知/胶囊」（`LiveNotificationInfo.Builder(...).showType(5)...`）。
- `framework.jar` 的 `android.app.Notification`：`FLAG_ALWAYS_SHOW_TICKER = 16777216`、`FLAG_ONLY_UPDATE_TICKER = 33554432`。

### 网易云音乐的做法（参照实现）

- `com.netease.cloudmusic.module.lyric.statuslyric.*`（classes3/18.dex）为 UI；`kh0/a`（classes18.dex）管理 Flyme 开关，键 `status_bar_lyric_setting_key`，开关变化时发广播 `status_bar_lyric_setting_change`。
- `kh0/b` 静态块反射读取 `Notification.FLAG_ALWAYS_SHOW_TICKER` / `FLAG_ONLY_UPDATE_TICKER`，`kh0.b.a()` 判断 Flyme 是否支持。
- 通知控制器 `d62/h.java:889-905`：
  `notification.tickerText = J0(当前歌词行); int i = notification.flags | kh0.b.b; notification.flags = i | kh0.b.c;`
  即「歌词 → tickerText，并或上两个 Flyme flag」。

**结论：任何播放器只要把当前歌词写进媒体通知的 `tickerText` 并或上 `0x01000000|0x02000000`，且通知不可清除、媒体会话处于播放态，就能获得 Flyme 状态栏歌词。** Apple Music 原生不做这件事，所以需要本模块补上。

### 一个重要的行为特性

SystemUI **只在通知被更新（notify）的那一刻**评估上述条件；若那一刻媒体不在播放态，该次会被拒绝且**不会自动重试**。因此模块不能只改一次通知，必须在歌词行切换时**自己克隆重发通知**。

---

## 二、Apple Music 侧逆向

### 1. 媒体通知

- media3 的 `MediaNotificationManager`（`E3/C1123r2`）固定以 **id = 1001**、channel `default_channel_id` 发布媒体通知；前台时走 `MediaPlaybackService.startForeground(1001, notification, 2)`（`E3/C1172z3`），否则 `NotificationManager.notify(1001, notification)`。
- `E3/N4` 构造通知：`Notification.MediaStyle().setMediaSession(token)`，并写入 extras `androidx.media3.session`（Bundle）与 `android.mediaSession`（token），这两个 extras 是判定「媒体通知」的稳定标记。
- 实测设备通知：`id=1001 flags=ONLY_ALERT_ONCE|NO_CLEAR category=transport groupKey=media3_group_key` —— **Apple Music 自带 NO_CLEAR，已满足 Flyme 的「不可清除」条件**。

### 2. 播放状态与队列

- `com.apple.android.music.playback.player.MediaPlayer`（`playback` 包全包未混淆，共 404 个文件）接口：`getCurrentPosition()`、`getPlaybackState()`、`getPlaybackQueueIndex()`、`getQueueItems()`、`getAllQueueItems()`、`getCurrentContainerStoreId()` 等。
- 实测 `getPlaybackState()`：**1 = 播放中**（position 递增），**2 = 暂停**（position 冻结）。
- `MediaPlayerFactory.create(...)` 是稳定 hook 点（内部 `new ExoMediaPlayer(...)`）。
- `PlayerQueueItem.getItem()` → `PlayerMediaItem`，其 `getPlaybackStoreId()` 就是 **adamId**（Apple Music 数字曲目 ID），也是歌词接口所需参数。

### 3. 歌词管线（TTML）

Apple Music 的歌词是 **TTML**（Apple 的逐词/逐行时间轴歌词格式）：

- 解析入口：`com.apple.android.music.ttml.javanative.TTMLParser$TTMLParserNative#songInfoFromTTML(String)`（javacpp native，返回 `SongInfo$SongInfoPtr`）—— 模块就是在这里拿到**完整 TTML 原文**。
- 拉取入口：`com.apple.android.music.player.viewmodel.PlayerLyricsViewModel#loadLyrics(PlaybackItem)`：若 `!item.hasLyrics() && !item.hasCustomLyrics()` 直接清空；否则 `Long.parseLong(item.getId())` 作为 adamId，带上 `getQueueId()` 和 `{当前系统歌词语言}` 起协程，在线走 `com.apple.android.music.ttml.f#e(adamId, scripts, queueId, languages, Continuation)`，离线走 `ttml.f#f(...)` 读本机加密歌词文件。
- **`com.apple.android.music.model.PlaybackItem` 是接口**，因此模块可以用 `java.lang.reflect.Proxy` 造一个假实现，直接触发 app 自己去联网拉歌词，**无需打开歌词页、无需用户处于歌词界面**。

---
## 三、模块实现（LSPosed / libxposed API 102）

### 1. 新 API 模块布局（LSPosed 2.2.x）

本模块使用 libxposed 新 API，不使用 legacy `assets/xposed_init`：

```
module/resources/META-INF/xposed/java_init.list   # com.amflymelyric.AmLyricModule
module/resources/META-INF/xposed/scope.list       # com.apple.android.music
module/resources/META-INF/xposed/module.prop      # minApiVersion=101 / targetApiVersion=102
```

入口类 `extends io.github.libxposed.api.XposedModule`，覆写 `onPackageLoaded(PackageLoadedParam)`；`AndroidManifest.xml` 里用 `android:label` / `android:description` 提供名称与简介（**缺少 `module.prop` 时 LSPosed 不会把 APK 认作模块**）。

### 2. Hook 一览（module/src/com/amflymelyric/AmLyricModule.java）

| 位置 | Hook 目标 | 作用 |
| --- | --- | --- |
| `AmLyricModule.java:93-105` | `NotificationManager.notify(int,Notification)` / `(String,int,Notification)` / `notifyAsPackage` | 捕获媒体通知，改写 `tickerText` 与 flags |
| `AmLyricModule.java:97-104` | `Service.startForeground(int,Notification)` / `(int,Notification,int)` | 前台媒体通知（Apple Music 走这条） |
| `AmLyricModule.java:148-170` | `playback.player.MediaPlayerFactory.create(...)` | 捕获 MediaPlayer 实例，启动 500ms 轮询 |
| `AmLyricModule.java:172-210` | `mediaapi.models.internals.Attributes.getTtml` / `getTtmlLocalizations` | 记录歌词 TTML 长度（辅助/诊断） |
| `AmLyricModule.java:211-233` | `PlayerLyricsViewModel.loadLyrics` / `setCurrentHighlightedLine` | 透传，并确认歌词加载被触发 |
| `AmLyricModule.java`（`hookTtml`） | `ttml.javanative.TTMLParser$TTMLParserNative.songInfoFromTTML(String)` | **拿到完整 TTML 原文** |

### 3. 歌词获取流程

1. 轮询线程每 500ms 读取 `MediaPlayer.getCurrentPosition()` / `getPlaybackState()`；
2. 曲目 = `getAllQueueItems().get(getPlaybackQueueIndex())`（**不能**用 `getQueueItems()`，那是重排后的列表），取其 `getPlaybackStoreId()` 作为 adamId；
3. 换曲时 `pushTicker(标题)` 先占位（避免残留上一首末行），随后 `requestLyrics(adamId)`：
   - 反射 `new PlayerLyricsViewModel(Application)`（`AmLyricModule.java:424-443`，实例缓存）；
   - 用 `Proxy` 造假 `PlaybackItem`（`ItemHandler`，`AmLyricModule.java:445-472`）：`hasLyrics=true`、`hasCustomLyrics=false`、`getId=adamId`、`getQueueId=0`、`offlineLyricsFilePath=""`；
   - 反射调用 `loadLyrics(PlaybackItem)` → app 自己联网拉 TTML；
4. `songInfoFromTTML` 回调里解析 `<p begin end dur>...</p>`（`onTtml` / `attr` / `stripTags` / `parseTime`，`AmLyricModule.java:476-637`），支持 `HH:MM:SS.mmm`、`m:ss.mmm`、纯毫秒/秒等写法，按 `begin` 排序成时间轴；
5. 轮询定位当前行，**行变化才** `pushTicker(text)`（`AmLyricModule.java:403-421`）：对每条已登记通知 `Notification copy = r.n.clone()`，设 `tickerText` + `flags |= 0x01000000|0x02000000`，再 `notify(id, copy)` / `notify(tag, id, copy)` **主动重发**。

### 4. 为什么必须主动重发

SystemUI 只在通知更新瞬间判定（见第一节末）。模块重发通知等价于「网易云每次换行更新通知」的行为，从而让状态栏持续刷新歌词。

---

## 四、构建、安装与启用

### 构建

```bash
# 依赖：JDK 25、Android SDK build-tools(36.1.0 或 37.0.0)、android.jar(platform 36)
bash tools/build_module.sh          # 输出 build/AMFlymeLyric.apk
```

脚本做的事：`javac -source 8 -target 8` → `d8` → `aapt2 compile/link` → 用 `jar` 打包 `classes.dex` 与 `META-INF/xposed/*` → `apksigner` 签名（自签名 `build/debug.keystore`，alias `amflyme`，口令 `android`）。

编译期需要 `build/libxposed-api-stub.jar`（由 framework.dex 反编译出的 `io.github.libxposed.api` 源码自建，因为无外网取 maven 依赖）；该 jar **只用于编译**，运行时使用框架注入的真实类。

### 安装与启用（不软重启）

```bash
adb install -r build/AMFlymeLyric.apk
```

安装后 LSPosed 会自动在 `/data/adb/lspd/config/modules_config.db` 的 `modules` 表登记本模块；随后打开 LSPosed 管理器 → AMFlymeLyric → 打开「启用模块」开关（若之前已开，**关一次再开一次**以触发配置热重载），作用域勾选 Apple Music。

> 注意：直接改 `modules_config.db`（`modules_state.enabled=1` + `scope` 行）**不会**让模块生效，必须经管理器开关触发一次配置热重载。

之后不需要重启手机，也不需要重启 SystemUI：force-stop Apple Music 再打开即可（必要时 `cmd media_session dispatch pause` 然后 `play`，让媒体通知在播放态重新发布）。

---
## 五、实测证据

日志（`adb logcat -d -s LSPosedFramework`，模块日志前缀 `AMFlymeLyric`）：

```
onPackageLoaded com.apple.android.music
hooks installed
captured MediaPlayer com.apple.android.music.playback.player.ExoMediaPlayer
created PlayerLyricsViewModel com.apple.android.music.player.viewmodel.PlayerLyricsViewModel@...
loadLyrics triggered for adamId=1443638411
Attributes.getTtmlLocalizations -> String len=15432
TTML#1 len=15432 lyrics=43
```

最终线上验收日志（播放《Numb》，adamId=1653074318，实测每行歌词按播放位置切换）：

```
track changed id=1792109795 title=Sincerely -> request lyrics
loadLyrics triggered for adamId=1792109795
Attributes.getTtmlLocalizations -> String len=3897
TTML#13 len=3897 head=<tt xmlns="http://www.w3.org/ns/ttml" ... itunes:timing="Li
TTML parsed paragraphs=33 first=766..7399 知らない言葉を 覚えていくたび
track changed id=1653074318 title=Numb -> request lyrics
lyric[0] Running in circles in my head
lyric[1] Like a spider spinning webs
```

设备通知（`dumpsys notification --noredact`）：媒体通知 `id=1001` 的 `tickerText` 已被替换为当前歌词行，`flags` 由 `0x8` 变为 `0x3000008`（含 `UNKNOWN(0x03000000)`）。

可见性截图（`build/`）：

| 文件 | 内容 |
| --- | --- |
| `shot_p4.png` | 注入测试串「AMFlymeLyric 状态栏歌词测试」显示在状态栏左侧 |
| `shot_v3.png` | 真实歌词行（如《保留》的「不去猜想我们隔多远」）在状态栏 |
| `cmp_pause.png` | 暂停时状态栏回到时钟 / 播放时显示歌词「只想让沉默的能开解」的对比 |
| `verify_statusbar2.png` | **最终线上验收**：播放《Numb》时状态栏显示滚动的 `Running in circles in my head`（左侧带 ♪ 图标，时钟被替换） |
| `final_status.png` | 一次未成功触发播放的验收截图（落在专辑页、非播放态，未拍到歌词） |

已验证行为：

- 换曲：立即用新曲标题占位，随后被真实 TTML 歌词行替换；
- 暂停：SystemUI 因媒体非播放态而隐藏歌词（系统行为，网易云同样如此）；继续播放即恢复；
- 歌词同时进入 Flyme 实况通知/胶囊（`LiveNotificationController.getLyricsNotifications`）。

### 开发过程中用到的 LSPosed DexSQL

`tools/dexsql.exe`（LSPosed/dexsql-release v0.1.0）用于直接 SQL 检索 APK 的 dex，例如

```bash
tools/dexsql.exe query --input apk/applemusic/base.apk --sql "SELECT class_descriptor,name,descriptor FROM methods WHERE class_descriptor LIKE '%TTMLParser%'"
# -> songInfoFromTTML(Ljava/lang/String;)Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;
```

以及确认 Apple Music 内**不存在**任何 `StatusBarLyric` 相关类型（即它完全没有适配 Flyme）。

---

## 六、已知限制与可改进项

> 第三方歌词源（「第三方歌词源」开关 / 网易云 provider / TTML 回灌）见 §九，它自身的限制也列在该节末尾。

1. **暂停即隐藏**：歌词显示受 SystemUI 媒体播放态门控，暂停时状态栏恢复时钟。这是 Flyme 系统行为（网易云亦然），模块未也无法改变。
2. **无歌词时显示标题**：目前换曲先占位标题；对无歌词曲目会一直显示标题。
3. **ticker_icon 已显式设置（v9）**：早期版本只在「新通知」里写了 tickerText、没写 `ticker_icon`，而 Flyme `AdvertTickerView`（out/systemui/sources/com/flyme/systemui/statusbar/ticker/AdvertTickerView.java:105-129）读到 `extras.getInt("ticker_icon") == 0` 时会 `setImageDrawable(null)` 隐藏图标，表现为「只有第一句有图标」。现在每条重发都写入 `ticker_icon` + `ticker_icon_switch=false`（同网易云：只在 id 变化时才切换，避免每句重播淡入）。取值按 `ICON_CANDIDATES`（按资源名查，跨 AM 版本安全）依次尝试：`mipmap/music_appicon_foreground`（实测 `0x7f0f0003`，AM App 图标那枚双音符，43px）→ `drawable/ic_audiotrack_light` → 通知自身 `smallIcon` → `ApplicationInfo.icon`。
   证据：logcat `hooks installed (v9 systemui_pad10)` / `ticker icon source=music_appicon_foreground resId=0x7f0f0003`；`dumpsys notification --noredact` 中 AM 两条媒体通知（id=2 / id=1001）extras 均含 `ticker_icon`/`ticker_icon_switch`(=false) 与 `flags=…UNKNOWN(0x03000000)`。

4. **图标区域宽度：在 SystemUI 侧裁掉死区（v9 修复，方案 B）**：`res/layout/status_bar_ticker_ext.xml`（`AdvertTickerView` 的真正布局，根节点就是该类）里 `ImageSwitcher id=tickerIcon_ext` 是 `layout_width=wrap_content` + `layout_height=match_parent` + `layout_marginEnd=2dp`，子 `AnimatedImageView` 同为 wrap_content × match_parent 且 `scaleType=7`（**CENTER_INSIDE，只缩不放**）。于是 **ImageSwitcher 的宽度 = drawable 的固有宽度**：`music_appicon_foreground` 108dp = 304px，图标只在里面居中画 35×43px，两侧各富余约 130px（歌词被顶到 x≈380）。SystemUI 不读任何尺寸键，且 AM 内没有「同款图标 + 固有宽度小」的资源，因此改为在 SystemUI 进程里后处理：
   hook `AdvertTickerView.addNotification(StatusBarNotification)` → 取 `mIconSwitcher` 的当前 `ImageView` → `iv.draw(canvas)` 把 SystemUI 刚画出来的图标（含主题染色）栅格化 → 取 alpha bbox → **左右各留 10px** 后裁剪 → `setImageDrawable(裁出来的 BitmapDrawable)` 写回。ImageView 是 wrap_content，重新测量后 ImageSwitcher 随新 intrinsic 收缩，歌词自动左移。
   实测（`/data/adb/lspd/log/verbose_*.log`）：`ticker icon 304x304 drawn 106x106 -> 55x44 (pad 10)`（每句都稳定复现）；截图实测 图标字形 35×43px、图标区域 **55px**、字形到歌词间距 ~15-19px（网易云为 34px / 61.9px / 19px，同一量级）。
   两个踩过的坑：①不能用标记位记住「已裁过」——SystemUI 重新 `setImageDrawable` 放回原图后标记还在，会漏裁（实测出现图标重新变宽）；②**不能按当前 ImageSwitcher 宽度截屏**——放回原图的那一刻容器可能还停在上一轮的窄宽度，会把 304dp 的图挤成 18×22 再被裁小（实测图标越裁越小）。现在改为「按 drawable 自身比例、以行高 106px 为上限重画，再用 `iv.getColorFilter()` 把主题染色烘进去，裁 alpha bbox 后左右各留 10px」；只有 `dw<=h && dh<=h`（已裁过或本来就小）才跳过。
   启用前置：模块 `META-INF/xposed/scope.list` 需含 `com.android.systemui`，并在 LSPosed 管理器里给模块勾选「系统界面」作用域，然后**重启一次 SystemUI 进程**（`kill -9 $(pidof com.android.systemui)`，Flyme 会自动拉起；不是软重启）。**坑**：LSPosed 守护进程把配置缓存在内存里，直接写 `/data/adb/lspd/config/modules_config.db`（scope/enabled）或重装模块都不会生效，必须由管理器触发一次配置重载——这一点已实测确认。
   hook 只对 `com.apple.android.music` 且带 `ticker_icon` 的通知生效，其它 App（含网易云）的 ticker 不受影响；全部逻辑包在 try/catch 内，异常只写日志。

5. **图标尺寸 43px 与 AM 资源无关了**：改造后可见大小由 SystemUI 侧裁剪结果决定（43px = App 图标固有字形尺寸），不再受「AM 内最小音符 50px」限制；若想改大小/留白，只需调 `AmLyricModule.doTrim()` 里的 `pad`（左右留白 px）或对裁剪结果再缩放。穷举脚本仍保留：矢量 `build/measure11.py`，位图 `build/scan_png.py` / `scan_png3.py` / `scan_png4.py`（431 个 PNG，输出每个候选的可见宽高与死区宽度）。
7. **强依赖 Apple Music 6.5.3 的内部类名**（`playback` 包未混淆，但 `E3/*` 等混淆名不含在内）；若 Apple Music 升级导致 `TTMLParserNative.songInfoFromTTML` 或 `PlayerLyricsViewModel.loadLyrics` 签名变化，需要重新适配。
8. **仅适配 Flyme**：判定逻辑写死了 `0x01000000|0x02000000` 与 Flyme ticker 机制；其它 ROM 的「状态栏歌词」实现方式不同。

---

9. **AM 设置 → 显示选项 里的「状态栏歌词」开关（v10）**：模块在 AM 设置页「显示选项」分组的最后插了一行开关（标题「状态栏歌词」，副标题「在 Flyme 状态栏显示当前歌词（AMFlymeLyric 模块）」，默认开），关掉后立刻清掉状态栏歌词并停止刷新，打开后立刻恢复。
   实现要点（这一段全是坑）：AM 里 `androidx.preference` 被 R8 混淆——`PreferenceFragmentCompat` 变成 `androidx.preference.b`，`Preference` 的方法名变成 `A/B/C…`，`getPreferenceCount()`、`getPreferenceScreen()`、`setOnPreferenceChangeListener()` 直接被删掉（只剩被 XML 引用的类名保留）。做法：①hook `addPreferencesFromResource(int)`（混淆后是 `androidx.preference.b->r1(I)V`）确认加载的是 `R.xml.basic_settings_preference`，再用 `findPreference`（`x0(String)Preference`）取 `key_display_options` 分组；②用「已知取值」认字段——`Preference.x`=key、`Preference.h`=title、`Preference.j`=summary、`Preference.c0`=widgetLayout（等于 `layout/switch_preference_layout` 0x7f0d07a5）；③新建 `CheckBoxPreference`（AM 自己的开关就是它）并套上用 AM 的 `switch_preference_layout`，才渲染出和「增强对比度」一样的开关（`SwitchPreferenceCompat` 在 AM 主题下渲染不出控件）；④变更回调靠 hook `TwoStatePreference.setChecked(boolean)`（`P(boolean)`），因为监听器接口失效；⑤`children 计数` 用 `getChild(i)` 越界试探（`getPreferenceCount` 已不存在），add/remove 靠调用后计数变化区分。
   状态落在 AM 的 `shared_prefs/amflymelyric.xml`（`status_lyric_enabled`，默认 true），与模块内 `lyricEnabled()` 一致。实测：logcat `status-lyric switch toggled -> false` → `ticker cleared` → 状态栏回到时钟；拨回 `-> true` → 状态栏立刻恢复歌词（截图 build/tap_on.png / build/tap_off.png）。

## 七、工程结构

```
I:/AMFlymeLyric/
├─ apk/netease/base.apk             网易云 9.6.05（机制参照）
├─ apk/applemusic/*.apk             Apple Music 6.5.3 base + splits
├─ system/systemui/SystemUI.apk     Flyme SystemUI（ticker 判定）
├─ system/framework/framework.jar   Flyme framework（两个 flag 常量）
├─ out/                             反编译源码（netease / systemui / fw / apple / lsposed_fw）
├─ module/
│   ├─ AndroidManifest.xml
│   ├─ res/values/strings.xml
│   ├─ resources/META-INF/xposed/{java_init.list,scope.list,module.prop}
│   ├─ src/com/amflymelyric/AmLyricModule.java   ← 模块主体（hook / ticker / 设置页开关 / 第三方词库注入）
│   ├─ src/com/amflymelyric/ThirdPartyLyrics.java ← 第三方词库（网易云搜索+打分+取词+LRC→TTML）
│   └─ tools/DeviceSql.java          设备端 SQLite 工具（模块启用调试用）
├─ tools/build_module.sh             构建脚本
├─ tools/dexsql.exe                  LSPosed DexSQL
├─ tools/dexstrings.mjs / dexclasses.mjs / ttmlinfo.mjs / dbdump.cjs
├─ build/scan_png.py / scan_png3.py / scan_png4.py  图标穷举：PNG 字形占比 + 固有宽度 + 死区宽度
├─ build/measure11.py               图标尺寸穷举：矢量 pathData bbox → 可见高度
└─ build/AMFlymeLyric.apk            模块产物（已签名）
```

---

## 八、快速验证步骤

```bash
# 1) 安装
adb install -r build/AMFlymeLyric.apk
# 2) LSPosed 管理器里启用模块（开关关→开），作用域勾选 Apple Music
# 3) 重启 Apple Music 进程
adb shell am force-stop com.apple.android.music
adb shell am start -n com.apple.android.music/.onboarding.activities.SplashActivity
# 4) 播放任意带歌词的歌曲（建议用 cmd media_session dispatch play 确保通知在播放态重发）
adb shell cmd media_session dispatch play
# 5) 看日志与状态栏
adb logcat -d -s LSPosedFramework | grep AMFlymeLyric
```

---
