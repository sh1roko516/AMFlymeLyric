package com.amflymelyric;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Apple Music -> Flyme status bar lyrics bridge (libxposed API 102).
 *
 * Flyme mechanism (reverse engineered from NetEase CloudMusic + SystemUI):
 *   notification.tickerText = current lyric line
 *   notification.flags |= 0x01000000 (FLAG_ALWAYS_SHOW_TICKER) | 0x02000000 (FLAG_ONLY_UPDATE_TICKER)
 *   notification must be non clearable (NO_CLEAR / ONGOING) and a media session must be playing.
 *   SystemUI: com.flyme.systemui.statusbar.ticker.AdvertTickerView.addNotification()
 */
public class AmLyricModule extends XposedModule {

    private static final String TAG = "AMFlymeLyric";
    private static final String TARGET = "com.apple.android.music";
    private static final int FLAG_ALWAYS_SHOW_TICKER = 0x01000000;
    private static final int FLAG_ONLY_UPDATE_TICKER = 0x02000000;
    private static final String TEST_TICKER = "AMFlymeLyric 状态栏歌词测试";
    private static final boolean TEST_MODE = false;
    private static final boolean DIAG = false;
    private static final char QUOTE = '"';

    private static volatile ClassLoader sCl;
    private static volatile Application sApp;
    private static volatile Object sPlayer;
    private static volatile Object sVm;
    private static volatile boolean sReporterStarted;
    private static volatile int sTtmlCount;
    private static volatile String sTickerText;
    private static volatile boolean sDumped;
    private static volatile String sAttrsTtml;

    private static final ArrayList<NotifRef> sNotifs = new ArrayList<NotifRef>();
    private static volatile String sLastMediaKey;
    private static volatile String sLastDiag;
    private static volatile boolean sExtrasDumped;
    private static volatile int sTickerIcon;
    private static volatile int sAppIcon;
    private static volatile int sFgIcon;
    private static volatile Boolean sLyricEnabled;
    private static volatile String sLastLyric;
    private static final String PREFS_NAME = "amflymelyric";
    private static final String PREF_STATUS_LYRIC = "status_lyric_enabled";
    private static final String PREF_THIRD_PARTY = "third_party_lyrics";

    // third-party lyric source state
    private static volatile Boolean sThirdOn;
    private static volatile String sThirdTtml;
    private static volatile String sThirdTrackId;
    private static volatile String sThirdCachePath;
    private static volatile int sThirdGen;
    private static volatile int sThirdLines;
    private static volatile boolean sThirdFetching;
    private static volatile boolean sThirdScheduled;
    private static final java.util.HashSet<String> sThirdFailed = new java.util.HashSet<String>();
    private static volatile boolean sOfficialTimed;
    private static volatile boolean sSelfTtmlCall;
    private static volatile Object sRealVm;
    private static volatile Object sRealItem;
    private static volatile String sTrackArtist = "";
    private static volatile String sTrackAlbum = "";
    private static volatile long sTrackDuration = 0L;
    private static volatile String sCurrentTitle = "";
    private static volatile String sInjectedKey = "";

    private static volatile String sTrackId;
    private static volatile long[] sBegins = new long[0];
    private static volatile long[] sEnds = new long[0];
    private static volatile String[] sTexts = new String[0];
    private static volatile int sCurLine = -2;
    private static volatile String sLoadId;
    private static volatile long sLoadAt;

    private static final class NotifRef {
        final int id;
        final String tag;
        final Notification n;
        NotifRef(int id, String tag, Notification n) { this.id = id; this.tag = tag; this.n = n; }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            if (SYSTEMUI.equals(pkg)) { hookSystemUi(param); return; }
            if (!TARGET.equals(pkg)) return;
            sCl = param.getDefaultClassLoader();
            log(Log.INFO, TAG, "onPackageLoaded " + pkg);
            hookNotifications();
            hookPlayerFactory();
            hookTtml();
            hookLyricsViewModel();
            hookSettings();
            log(Log.INFO, TAG, "hooks installed (v12 multisource)");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageLoaded failed", t);
        }
    }

    // ------------------------------------------------------------------ hooks

    private void hookNotifications() {
        hookNotifyMethod("notify", new Class[]{int.class, Notification.class});
        hookNotifyMethod("notify", new Class[]{String.class, int.class, Notification.class});
        hookNotifyMethod("notifyAsPackage", new Class[]{String.class, String.class, int.class, Notification.class});
        try {
            Method m = Service.class.getDeclaredMethod("startForeground", int.class, Notification.class);
            hookMethod(m, new ForegroundHook());
        } catch (Throwable t) { log(Log.ERROR, TAG, "hook startForeground(2)", t); }
        try {
            Method m = Service.class.getDeclaredMethod("startForeground", int.class, Notification.class, int.class);
            hookMethod(m, new ForegroundHook());
        } catch (Throwable t) { log(Log.ERROR, TAG, "hook startForeground(3)", t); }
    }

    private void hookNotifyMethod(String name, Class[] params) {
        try {
            Method m = NotificationManager.class.getDeclaredMethod(name, params);
            hookMethod(m, new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) {
                    try {
                        List args = chain.getArgs();
                        Notification n = null;
                        int id = -1;
                        String tag = null;
                        for (int i = 0; i < args.size(); i++) {
                            Object a = args.get(i);
                            if (a instanceof Notification) n = (Notification) a;
                            else if (a instanceof Integer) id = ((Integer) a).intValue();
                            else if (a instanceof String && tag == null) tag = (String) a;
                        }
                        onNotify(id, tag, n);
                    } catch (Throwable t) { log(Log.ERROR, TAG, "notify hook", t); }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "hook NotificationManager." + name, t); }
    }

    private final class ForegroundHook implements XposedInterface.Hooker {
        public Object intercept(XposedInterface.Chain chain) {
            try {
                List args = chain.getArgs();
                Notification n = null;
                int id = -1;
                for (int i = 0; i < args.size(); i++) {
                    Object a = args.get(i);
                    if (a instanceof Notification) n = (Notification) a;
                    else if (a instanceof Integer) id = ((Integer) a).intValue();
                }
                onNotify(id, null, n);
            } catch (Throwable t) { log(Log.ERROR, TAG, "startForeground hook", t); }
            return chain.proceed();
        }
    }

    private void hookPlayerFactory() {
        try {
            Class<?> cls = sCl.loadClass("com.apple.android.music.playback.player.MediaPlayerFactory");
            Method[] ms = cls.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                final Method m = ms[i];
                if (!"create".equals(m.getName())) continue;
                log(Log.INFO, TAG, "hooking " + m);
                hookMethod(m, new XposedInterface.Hooker() {
                    public Object intercept(XposedInterface.Chain chain) {
                        Object r = null;
                        try { r = chain.proceed(); } catch (Throwable t) { log(Log.ERROR, TAG, "create failed", t); return null; }
                        if (r != null) {
                            sPlayer = r;
                            log(Log.INFO, TAG, "captured MediaPlayer " + r.getClass().getName());
                            startReporter();
                        }
                        return r;
                    }
                });
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "hookPlayerFactory", t); }
    }

    private void hookTtml() {
        try {
            Class<?> attrs = sCl.loadClass("com.apple.android.music.mediaapi.models.internals.Attributes");
            final String[] names = new String[]{"getTtml", "getTtmlLocalizations"};
            for (int i = 0; i < names.length; i++) {
                try {
                    final Method m = attrs.getDeclaredMethod(names[i], new Class[0]);
                    hookMethod(m, new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) {
                            Object r = chain.proceed();
                            try {
                                if (r instanceof String) {
                                    String s = (String) r;
                                    sAttrsTtml = s;
                                    boolean timed = ThirdPartyLyrics.hasTimedLines(s);
                                    if (!sSelfTtmlCall) {
                                        sOfficialTimed = timed;
                                        if (!timed && thirdPartyEnabled()) thirdOfficialUntimed();
                                        log(Log.INFO, TAG, "Attributes." + chain.getExecutable().getName()
                                                + " -> len=" + s.length() + " timed=" + timed);
                                    }
                                    String repl = sThirdTtml;
                                    if (thirdPartyEnabled() && !timed && repl != null && repl.length() > 0) {
                                        sAttrsTtml = repl;
                                        log(Log.INFO, TAG, "third-party TTML injected via Attributes."
                                                + chain.getExecutable().getName());
                                        return repl;
                                    }
                                } else if (r != null) {
                                    log(Log.INFO, TAG, "Attributes." + chain.getExecutable().getName()
                                            + " -> " + r.getClass().getName());
                                } else if (!sSelfTtmlCall && thirdPartyEnabled() && sThirdTtml != null) {
                                    log(Log.INFO, TAG, "Attributes." + chain.getExecutable().getName()
                                            + " -> null, injecting third-party TTML");
                                    sAttrsTtml = sThirdTtml;
                                    return sThirdTtml;
                                }
                            } catch (Throwable t) { log(Log.ERROR, TAG, "ttml attr hook", t); }
                            return r;
                        }
                    });
                } catch (Throwable t) { log(Log.ERROR, TAG, "hook Attributes." + names[i], t); }
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "hookTtml/Attributes", t); }

        try {
            Class<?> p = sCl.loadClass("com.apple.android.music.ttml.javanative.TTMLParser$TTMLParserNative");
            Method m = p.getDeclaredMethod("songInfoFromTTML", String.class);
            log(Log.INFO, TAG, "hooking " + m);
            hookMethod(m, new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) {
                    Object a = chain.getArg(0);
                    if (a instanceof String) {
                        String s = (String) a;
                        String repl = sThirdTtml;
                        if (!sSelfTtmlCall) {
                            sOfficialTimed = ThirdPartyLyrics.hasTimedLines(s);
                            if (!sOfficialTimed && thirdPartyEnabled()) thirdOfficialUntimed();
                        }
                        if (thirdPartyEnabled() && !ThirdPartyLyrics.hasTimedLines(s)
                                && repl != null && repl.length() > 0) {
                            log(Log.INFO, TAG, "third-party TTML replaces parser input");
                            onTtml(repl);
                            return chain.proceed(new Object[]{repl});
                        }
                        onTtml(s);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "hook TTMLParserNative", t); }
    }

    private void hookLyricsViewModel() {
        try {
            Class<?> vm = sCl.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
            Method m = vm.getDeclaredMethod("setCurrentHighlightedLine", int.class);
            hookMethod(m, new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) {
                    return chain.proceed();
                }
            });
            Class<?> itemCls = sCl.loadClass("com.apple.android.music.model.PlaybackItem");
            Method l = vm.getDeclaredMethod("loadLyrics", itemCls);
            log(Log.INFO, TAG, "hooking " + l);
            hookMethod(l, new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) {
                    Object item = chain.getArg(0);
                    if (item != null && !Proxy.isProxyClass(item.getClass())) {
                        sRealVm = chain.getThisObject();
                        sRealItem = item;
                        try { prepareThirdPartyItem(item); } catch (Throwable t) { log(Log.ERROR, TAG, "prepareThirdPartyItem", t); }
                    }
                    if (TEST_MODE) log(Log.INFO, TAG, "app called loadLyrics(" + chain.getArg(0) + ")");
                    Object r = chain.proceed();
                    try {
                        if (thirdPartyEnabled() && !sOfficialTimed && sThirdTtml != null) {
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                public void run() {
                                    try { injectThirdIntoRealVm(); } catch (Throwable t) { log(Log.ERROR, TAG, "inject after loadLyrics", t); }
                                }
                            }, 700L);
                        }
                    } catch (Throwable t) { }
                    return r;
                }
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "hookLyricsViewModel", t); }
    }

    private XposedInterface.HookHandle hookMethod(Method m, XposedInterface.Hooker h) {
        try {
            return hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT).intercept(h);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + m, t);
            return null;
        }
    }

    // ---------------------------------------------------------------- notification

    private void onNotify(int id, String tag, Notification n) {
        if (n == null) return;
        try {
            boolean media = n.extras != null && (n.extras.containsKey("android.mediaSession")
                    || n.extras.containsKey("androidx.media3.session"));
            if (!media && id != 1001 && id != 2) return;
            applyTickerIcon(n);
            if (TEST_MODE) {
                log(Log.INFO, TAG, "media notif id=" + id + " tag=" + tag + " flags=0x"
                        + Integer.toHexString(n.flags));
            }
            if (DIAG && !sExtrasDumped) {
                sExtrasDumped = true;
                StringBuilder sb = new StringBuilder("extras:");
                for (String k : n.extras.keySet()) {
                    Object v = n.extras.get(k);
                    String s = v instanceof Bundle ? "Bundle" : String.valueOf(v);
                    if (s.length() > 160) s = s.substring(0, 160) + "...";
                    sb.append(" [").append(k).append("=").append(s).append("]");
                }
                log(Log.INFO, TAG, sb.toString());
                Bundle mb = n.extras.getBundle("androidx.media3.session");
                if (mb != null) {
                    StringBuilder s2 = new StringBuilder("media3:");
                    for (String k : mb.keySet()) {
                        Object v = mb.get(k);
                        String s = v instanceof Bundle ? "Bundle" : String.valueOf(v);
                        if (s.length() > 200) s = s.substring(0, 200) + "...";
                        s2.append(" [").append(k).append("=").append(s).append("]");
                    }
                    log(Log.INFO, TAG, s2.toString());
                }
            }
            String key = id + "/" + tag;
            if (!key.equals(sLastMediaKey)) {
                sLastMediaKey = key;
                log(Log.INFO, TAG, "tracking media notification " + key);
            }
            synchronized (sNotifs) {
                boolean found = false;
                for (int i = 0; i < sNotifs.size(); i++) {
                    NotifRef r = sNotifs.get(i);
                    if (r.id == id && (r.tag == null ? tag == null : r.tag.equals(tag))) {
                        sNotifs.set(i, new NotifRef(id, tag, n));
                        found = true;
                        break;
                    }
                }
                if (!found) sNotifs.add(new NotifRef(id, tag, n));
                while (sNotifs.size() > 4) sNotifs.remove(0);
            }
            if (sTickerText != null && lyricEnabled()) {
                n.tickerText = sTickerText;
                n.flags |= FLAG_ALWAYS_SHOW_TICKER | FLAG_ONLY_UPDATE_TICKER;
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "onNotify", t); }
    }

    /** Flyme ticker icon: SystemUI reads extras.getInt("ticker_icon") + getBoolean("ticker_icon_switch")
     *  from the notification and loads that resource id from the notification's own package. */
    private static int iconRes(Notification n) {
        try {
            Icon ic = n.getSmallIcon();
            if (ic != null && ic.getType() == Icon.TYPE_RESOURCE) return ic.getResId();
        } catch (Throwable t) { }
        try { if (n.icon != 0) return n.icon; } catch (Throwable t) { }
        return 0;
    }

    /** 图标候选（按名字查，跨版本安全）。顺序即优先级。
     *  挑选标准：SystemUI 的 ImageSwitcher 是 wrap_content × match_parent + CENTER_INSIDE（只缩不放），
     *  所以 view 宽度 = drawable 的固有宽度；固有宽度越大，图标两侧的死区越宽。
     *  ic_audiotrack_light 24dp（固有 67.5px）→ 音符 51px、两侧各约 15px 死区；
     *  music_appicon_foreground 108dp（被行高夹到 105px）→ 音符 43px 但两侧各约 135px 死区。 */
    private static final String[][] ICON_CANDIDATES = {
        {"music_appicon_foreground", "mipmap"},
        {"ic_audiotrack_light", "drawable"},
    };

    private int fgIconRes() {
        if (sFgIcon != 0) return sFgIcon;
        try {
            Application a = app();
            if (a == null) return 0;
            for (String[] c : ICON_CANDIDATES) {
                int id = a.getResources().getIdentifier(c[0], c[1], a.getPackageName());
                if (id != 0) {
                    sFgIcon = id;
                    log(Log.INFO, TAG, "ticker icon source=" + c[0] + " resId=0x" + Integer.toHexString(id));
                    break;
                }
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "fgIconRes", t); }
        return sFgIcon;
    }

    /** Last resort: Apple Music's own launcher icon resource id (resolved in com.apple.android.music). */
    private int appIconRes() {
        if (sAppIcon != 0) return sAppIcon;
        try {
            Application a = app();
            if (a != null) {
                sAppIcon = a.getPackageManager().getApplicationInfo(a.getPackageName(), 0).icon;
                log(Log.INFO, TAG, "app icon resId=0x" + Integer.toHexString(sAppIcon));
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "appIconRes", t); }
        return sAppIcon;
    }

    private void applyTickerIcon(Notification n) {
        if (n == null) return;
        int ir = fgIconRes();          // mipmap/music_appicon_foreground：白色单色音符，四周留白，大小对齐网易云
        if (ir == 0) ir = iconRes(n);  // 回退：媒体通知 smallIcon (media3_notification_small_icon)
        if (ir == 0) ir = appIconRes();
        if (ir != 0) {
            if (ir != sTickerIcon) log(Log.INFO, TAG, "ticker_icon resId=0x" + Integer.toHexString(ir));
            sTickerIcon = ir;
        }
        if (sTickerIcon == 0) return;
        if (n.extras == null) n.extras = new Bundle();
        n.extras.putInt("ticker_icon", sTickerIcon);
        n.extras.putBoolean("ticker_icon_switch", false);   // 同网易云：仅在 id 变化时切换，避免每句重播淡入淡出
    }

    // ---------------------------------------------------------------- reporter

    private void startReporter() {
        if (sReporterStarted) return;
        sReporterStarted = true;
        Thread th = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    try { tick(); } catch (Throwable t) { log(Log.ERROR, TAG, "tick", t); }
                }
            }
        }, "AMFlymeLyric-reporter");
        th.setDaemon(true);
        th.start();
    }

    private void tick() {
        Object p = sPlayer;
        if (p == null) return;
        long pos = num(invoke(p, "getCurrentPosition"), -1);
        Object items = invoke(p, "getAllQueueItems");
        if (!(items instanceof List)) items = invoke(p, "getQueueItems");
        int idx = (int) num(invoke(p, "getPlaybackQueueIndex"), -1);
        String id = null;
        String title = null;
        Object itemObj = null;
        if (items instanceof List) {
            List l = (List) items;
            if (idx >= 0 && idx < l.size()) {
                Object item = invoke(l.get(idx), "getItem");
                if (item != null) {
                    id = str(invoke(item, "getPlaybackStoreId"));
                    title = str(invoke(item, "getTitle"));
                    itemObj = item;
                }
            }
        }
        if (itemObj != null) {
            String ar = str(invoke(itemObj, "getArtistName"));
            String al = str(invoke(itemObj, "getAlbumTitle"));
            if (ar != null) sTrackArtist = ar;
            if (al != null) sTrackAlbum = al;
            long dur = num(invoke(itemObj, "getDuration"), 0L);
            if (dur > 0) sTrackDuration = dur;
        }
        if (id != null && !id.equals(sTrackId)) {
            sTrackId = id;
            synchronized (this) {
                sBegins = new long[0];
                sEnds = new long[0];
                sTexts = new String[0];
                sCurLine = -2;
            }
            log(Log.INFO, TAG, "track changed id=" + id + " title=" + title + " -> request lyrics");
            if (DIAG) diagQueue(p, idx, items);
            if (title != null && title.length() > 0) pushTicker(title);
            sCurrentTitle = title == null ? "" : title;
            requestLyrics(id);
            try { thirdOnTrack(id, title); } catch (Throwable t) { log(Log.ERROR, TAG, "thirdOnTrack", t); }
        } else if (id != null && sTexts.length == 0 && id.equals(sLoadId)
                && System.currentTimeMillis() - sLoadAt > 20000) {
            log(Log.INFO, TAG, "retry lyrics for " + id);
            requestLyrics(id);
        }
        long[] begins = sBegins;
        long[] ends = sEnds;
        String[] texts = sTexts;
        if (begins.length == 0 || pos < 0) return;
        int line = -1;
        for (int i = 0; i < begins.length; i++) {
            if (pos >= begins[i]) line = i; else break;
        }
        if (line >= 0 && line < begins.length) {
            long end = ends[line];
            if (end > 0 && pos > end + 1500) return;
        }
        if (line != sCurLine) {
            sCurLine = line;
            String text = line >= 0 ? texts[line] : null;
            if (text != null) {
                log(Log.INFO, TAG, "lyric[" + line + "] " + text);
                pushTicker(text);
            }
        }
    }

    private void diagQueue(Object p, int idx, Object items) {
        try {
            StringBuilder sb = new StringBuilder("DIAG queue idx=" + idx);
            if (items instanceof List) {
                List l = (List) items;
                sb.append(" size=").append(l.size());
                for (int k = Math.max(0, idx - 1); k <= Math.min(l.size() - 1, idx + 1); k++) {
                    Object it = invoke(l.get(k), "getItem");
                    sb.append(" |Q[").append(k).append("]=").append(it == null ? "null"
                            : str(invoke(it, "getTitle")) + "/" + str(invoke(it, "getPlaybackStoreId")));
                }
            }
            Object all = invoke(p, "getAllQueueItems");
            if (all instanceof List) {
                List al = (List) all;
                sb.append(" allSize=").append(al.size());
                for (int k = Math.max(0, idx - 1); k <= Math.min(al.size() - 1, idx + 1); k++) {
                    Object it = invoke(al.get(k), "getItem");
                    sb.append(" |A[").append(k).append("]=").append(it == null ? "null"
                            : str(invoke(it, "getTitle")) + "/" + str(invoke(it, "getPlaybackStoreId")));
                }
            }
            sb.append(" container=").append(invoke(p, "getCurrentContainerIndex"))
              .append("/").append(invoke(p, "getCurrentContainerStoreId"))
              .append(" count=").append(invoke(p, "getPlaybackItemCount"));
            log(Log.INFO, TAG, sb.toString());
        } catch (Throwable t) { log(Log.ERROR, TAG, "diagQueue", t); }
    }

    private void pushTicker(String text) {
        if (text == null) return;
        sLastLyric = text;
        sTickerText = text;
        if (!lyricEnabled()) return;              // 设置页「显示选项」里的开关关掉时不刷状态栏
        Application a = app();
        if (a == null) return;
        ArrayList<NotifRef> refs;
        synchronized (sNotifs) { refs = new ArrayList<NotifRef>(sNotifs); }
        NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        for (int i = 0; i < refs.size(); i++) {
            NotifRef r = refs.get(i);
            try {
                Notification copy = r.n.clone();
                applyTickerIcon(copy);
                copy.tickerText = text;
                copy.flags |= FLAG_ALWAYS_SHOW_TICKER | FLAG_ONLY_UPDATE_TICKER;
                if (r.tag == null) nm.notify(r.id, copy); else nm.notify(r.tag, r.id, copy);
            } catch (Throwable t) { log(Log.ERROR, TAG, "pushTicker id=" + r.id, t); }
        }
    }

    // ---------------------------------------------------------------- lyrics load

    private void requestLyrics(String adamId) {
        try {
            synchronized (this) { sLoadId = adamId; sLoadAt = System.currentTimeMillis(); }
            Object vm = sVm;
            if (vm == null) {
                Class<?> vmCls = sCl.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
                Constructor c = vmCls.getConstructor(Application.class);
                vm = c.newInstance(app());
                sVm = vm;
                log(Log.INFO, TAG, "created PlayerLyricsViewModel " + vm);
            }
            Class<?> itemCls = sCl.loadClass("com.apple.android.music.model.PlaybackItem");
            Object proxy = Proxy.newProxyInstance(sCl, new Class[]{itemCls}, new ItemHandler(adamId));
            Method load = vm.getClass().getMethod("loadLyrics", new Class[]{itemCls});
            load.invoke(vm, new Object[]{proxy});
            log(Log.INFO, TAG, "loadLyrics triggered for adamId=" + adamId);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "requestLyrics(" + adamId + ")", t);
        }
    }

    private final class ItemHandler implements InvocationHandler {
        private final String mId;
        ItemHandler(String id) { mId = id; }
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            if ("hasLyrics".equals(n)) return Boolean.TRUE;
            if ("hasCustomLyrics".equals(n)) return Boolean.FALSE;
            if ("getId".equals(n)) return mId;
            if ("getQueueId".equals(n)) return Long.valueOf(0L);
            if ("getTitle".equals(n)) return "AMLyric";
            if ("offlineLyricsFilePath".equals(n)) return "";
            if ("isDownloaded".equals(n)) return Boolean.FALSE;
            if ("isPlaying".equals(n)) return Boolean.TRUE;
            if ("toString".equals(n)) return "AMLyricItem(" + mId + ")";
            if ("hashCode".equals(n)) return Integer.valueOf(mId.hashCode());
            if ("equals".equals(n)) return Boolean.valueOf(proxy == args[0]);
            Class rt = method.getReturnType();
            if (rt == Boolean.TYPE) return Boolean.FALSE;
            if (rt == Integer.TYPE) return Integer.valueOf(0);
            if (rt == Long.TYPE) return Long.valueOf(0L);
            if (rt == Float.TYPE) return Float.valueOf(0f);
            if (rt == Double.TYPE) return Double.valueOf(0d);
            if (rt == Short.TYPE) return Short.valueOf((short) 0);
            if (rt == Byte.TYPE) return Byte.valueOf((byte) 0);
            if (rt == Character.TYPE) return Character.valueOf((char) 0);
            return null;
        }
    }

    // ---------------------------------------------------------------- ttml

    private void onTtml(String xml) {
        try {
            int c = ++sTtmlCount;
            if (xml == null) { log(Log.INFO, TAG, "TTML#" + c + " null"); return; }
            log(Log.INFO, TAG, "TTML#" + c + " len=" + xml.length() + " head="
                    + xml.substring(0, Math.min(160, xml.length())).replace('\n', ' '));
            if (!sSelfTtmlCall) dump("ttml_" + c + ".xml", xml);
            int[] spans = new int[2];
            long[] begins = new long[0];
            long[] ends = new long[0];
            String[] texts = new String[0];
            ArrayList<long[]> bl = new ArrayList<long[]>();
            ArrayList<String> tl = new ArrayList<String>();
            scanParagraphs(xml, bl, tl);
            int n = bl.size();
            begins = new long[n];
            ends = new long[n];
            texts = new String[n];
            for (int i = 0; i < n; i++) {
                begins[i] = bl.get(i)[0];
                ends[i] = bl.get(i)[1];
                texts[i] = tl.get(i);
            }
            String sample = n > 0 ? (begins[0] + ".." + ends[0] + " " + texts[0]) : "-";
            log(Log.INFO, TAG, "TTML parsed paragraphs=" + n + " first=" + sample);
            if (n > 0) {
                synchronized (this) {
                    sBegins = begins;
                    sEnds = ends;
                    sTexts = texts;
                    sCurLine = -2;
                }
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "onTtml", t); }
    }

    /** very tolerant tt(p) paragraph scan: begin/end/dur + text of the paragraph */
    private void scanParagraphs(String xml, ArrayList<long[]> out, ArrayList<String> texts) {
        int i = 0;
        String lower = xml;
        while (i < xml.length()) {
            int p = lower.indexOf("<p ", i);
            int p2 = lower.indexOf("<p>", i);
            if (p < 0 || (p2 >= 0 && p2 < p)) p = p2;
            if (p < 0) break;
            int gt = xml.indexOf('>', p);
            if (gt < 0) break;
            String attrs = xml.substring(p, gt);
            int gtc = xml.indexOf("</p>", gt);
            if (gtc < 0) gtc = xml.length();
            String inner = xml.substring(gt + 1, gtc);
            i = gtc + 4;
            long b = parseTime(attr(attrs, "begin"));
            long e = parseTime(attr(attrs, "end"));
            if (e < 0) {
                long d = parseTime(attr(attrs, "dur"));
                if (d > 0 && b >= 0) e = b + d;
            }
            String text = stripTags(inner);
            if (b < 0 || text.length() == 0) continue;
            if (e <= b) e = b + 3000;
            long[] span = new long[]{b, e};
            out.add(span);
            texts.add(text);
        }
        // keep order by begin
        for (int a = 1; a < out.size(); a++) {
            long[] sp = out.get(a);
            String tx = texts.get(a);
            int j = a - 1;
            while (j >= 0 && out.get(j)[0] > sp[0]) {
                out.set(j + 1, out.get(j));
                texts.set(j + 1, texts.get(j));
                j--;
            }
            out.set(j + 1, sp);
            texts.set(j + 1, tx);
        }
    }

    private static String attr(String attrs, String name) {
        int i = attrs.indexOf(name + "=");
        if (i < 0) return null;
        int q = attrs.indexOf('"', i);
        char qc = '"';
        if (q < 0) { q = attrs.indexOf('\'', i); qc = '\''; }
        if (q < 0) return null;
        int e = attrs.indexOf(qc, q + 1);
        if (e < 0) return null;
        return attrs.substring(q + 1, e);
    }

    private static String stripTags(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\uFEFF') { i++; continue; }
            if (c == '<') {
                int gt = s.indexOf('>', i);
                if (gt < 0) break;
                i = gt + 1;
                continue;
            }
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 8) {
                    String ent = s.substring(i + 1, semi);
                    if ("amp".equals(ent)) sb.append('&');
                    else if ("lt".equals(ent)) sb.append('<');
                    else if ("gt".equals(ent)) sb.append('>');
                    else if ("quot".equals(ent)) sb.append('"');
                    else if ("apos".equals(ent) || "#39".equals(ent)) sb.append('\'');
                    else if ("nbsp".equals(ent)) sb.append(' ');
                    else if (ent.startsWith("#")) {
                        try {
                            int cp = ent.startsWith("#x") || ent.startsWith("#X")
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            sb.appendCodePoint(cp);
                        } catch (Throwable t) { sb.append(' '); }
                    } else sb.append(' ');
                    i = semi + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        String t = sb.toString().trim().replaceAll("\\s+", " ");
        while (t.length() > 1 && t.charAt(0) == QUOTE) t = t.substring(1).trim();
        while (t.length() > 1 && t.charAt(t.length() - 1) == QUOTE) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private static long parseTime(String v) {
        if (v == null) return -1;
        v = v.trim();
        if (v.length() == 0) return -1;
        try {
            if (v.indexOf(':') >= 0) {
                String[] parts = v.split(":");
                double sec = 0, mult = 1;
                for (int k = parts.length - 1; k >= 0; k--) {
                    String s = parts[k].trim();
                    double d = s.length() == 0 ? 0 : Double.parseDouble(s);
                    sec += d * mult;
                    mult *= 60;
                }
                return (long) (sec * 1000);
            }
            if (v.endsWith("ms")) return (long) Double.parseDouble(v.substring(0, v.length() - 2));
            if (v.endsWith("s")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 1000);
            if (v.endsWith("m")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 60000);
            if (v.endsWith("h")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 3600000);
            return (long) (Double.parseDouble(v) * 1000);
        } catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------ SystemUI (icon area width)

    private static final String SYSTEMUI = "com.android.systemui";

    /** Flyme's ticker reserves the *drawable's intrinsic width*: res/layout/status_bar_ticker_ext.xml
     *  declares the ImageSwitcher wrap_content x match_parent with an AnimatedImageView child
     *  (scaleType=CENTER_INSIDE, which never upscales). A 108dp artwork therefore books 304px
     *  of status bar while its glyph paints only ~35px in the middle - the rest is dead space.
     *  SystemUI exposes no size hint for ticker_icon, so we post-process here: rasterise what
     *  SystemUI just drew, crop it to the glyph and put the crop back, which collapses the
     *  reserved width onto the glyph (the ImageView re-measures to the new intrinsic size). */
    private void hookSystemUi(XposedModuleInterface.PackageLoadedParam param) {
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            Class<?> cls = cl.loadClass("com.flyme.systemui.statusbar.ticker.AdvertTickerView");
            Method m = cls.getDeclaredMethod("addNotification", StatusBarNotification.class);
            m.setAccessible(true);
            hook(m).setPriority(XposedInterface.PRIORITY_DEFAULT).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    try {
                        if (Boolean.TRUE.equals(ret)) trimTickerIcon(chain.getThisObject(), chain.getArg(0));
                    } catch (Throwable t) { log(Log.ERROR, TAG, "trimTickerIcon", t); }
                    return ret;
                }
            });
            log(Log.INFO, TAG, "systemui ticker hook installed");
        } catch (Throwable t) { log(Log.ERROR, TAG, "hookSystemUi", t); }
    }

    private void trimTickerIcon(Object tickerView, Object sbnObj) {
        if (!(sbnObj instanceof StatusBarNotification)) return;
        StatusBarNotification sbn = (StatusBarNotification) sbnObj;
        if (!TARGET.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        if (n.extras.getInt("ticker_icon") == 0) return;
        Object o = getField(tickerView, "mIconSwitcher");
        if (!(o instanceof ImageSwitcher)) return;
        final ImageSwitcher is = (ImageSwitcher) o;
        try { is.setMeasureAllChildren(false); } catch (Throwable t) { }
        final View cur = is.getCurrentView();
        if (!(cur instanceof ImageView)) return;
        final ImageView iv = (ImageView) cur;
        if (iv.getWidth() > 0 && iv.getHeight() > 0) { doTrim(iv, cur); return; }
        cur.post(new Runnable() {
            public void run() { try { doTrim(iv, cur); } catch (Throwable t) { log(Log.ERROR, TAG, "doTrim(post)", t); } }
        });
    }

    private void doTrim(ImageView iv, View cur) {
        int h = iv.getHeight();
        if (h <= 0) return;
        Drawable d = iv.getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        // 已经裁过（或本来就比行高小）就不再重复处理：SystemUI 重新 setImageDrawable 会把原图放回来，
        // 所以不能用标记位判断，只能看固有尺寸。
        if (dw <= h && dh <= h) return;
        // 关键：不要按当前 ImageSwitcher 的宽度截屏——SystemUI 放回原图的那一刻，容器可能还停留在
        // 上一轮的窄宽度，那样会把原图挤成一个小图（实测出现过 304dp 图被挤成 18x22 再被裁小）。
        // 这里按 drawable 自身比例、以行高为上限重画，等效于宽容器里的正常渲染。
        int rw, rh;
        if (dw >= dh) { rw = h; rh = Math.max(1, (int) Math.round((double) dh * h / dw)); }
        else { rh = h; rw = Math.max(1, (int) Math.round((double) dw * h / dh)); }
        Bitmap full = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(full);
        d.setBounds(0, 0, rw, rh);
        android.graphics.ColorFilter cf = iv.getColorFilter() != null ? iv.getColorFilter() : d.getColorFilter();
        if (cf != null) d.setColorFilter(cf);
        d.draw(canvas);
        int minX = rw, minY = rh, maxX = -1, maxY = -1;
        int[] px = new int[rw * rh];
        full.getPixels(px, 0, rw, 0, 0, rw, rh);
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                if ((px[y * rw + x] >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return;
        int pad = 10;                                  // 左右各留 10px 呼吸位，不要把死区一次裁光
        int x0 = Math.max(0, minX - pad);
        int x1 = Math.min(rw - 1, maxX + pad);
        int cw = x1 - x0 + 1, ch = maxY - minY + 1;
        Bitmap cropped = Bitmap.createBitmap(full, x0, minY, cw, ch);
        iv.setImageDrawable(new BitmapDrawable(iv.getResources(), cropped));
        log(Log.INFO, TAG, "ticker icon " + dw + "x" + dh + " drawn " + rw + "x" + rh + " -> " + cw + "x" + ch + " (pad " + pad + ")");
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        Class c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    // -------------------------------------------- AM 设置页「显示选项」里的开关

    /** 挂在 androidx.preference 的 setPreferenceScreen 上：AM 的所有设置页都是
     *  PreferenceFragmentCompat（com.apple.android.music.settings.fragment.p extends
     *  androidx.preference.b），screen 建好后找到「显示选项」那个 category 插一行开关。 */
    /** androidx.preference 在 AM 里被 R8 混淆：PreferenceFragmentCompat 变成 androidx.preference.b，
     *  Preference 的方法名被改写成 A/B/C…，只有被 XML 引用的类名（Preference/Screen/Group/Category/
     *  CheckBoxPreference/SwitchPreferenceCompat）保留。所以这里不认名字：先挂到「设置 Fragment 的
     *  screen setter」（结构化：1 参 void 且参数类型是 PreferenceScreen），再用「结构 + 已知取值」
     *  把 getKey/getChild/getTitle/... 探出来，最后把 SwitchPreferenceCompat 插进「显示选项」分组。 */
    private void hookSettings() {
        try {
            Class<?> base = sCl.loadClass("com.apple.android.music.settings.fragment.p");   // AM 所有设置页的基类
            int hooked = 0;
            for (Class<?> c = base.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    m.setAccessible(true);
                    // r1(I)V == addPreferencesFromResource(int)（R8 把 setPreferenceScreen 内联掉后仅剩这个）
                    if (ps.length == 1 && ps[0] == int.class && m.getReturnType() == void.class) {
                        hookMethod(m, new ResourceHook());
                        hooked++;
                        log(Log.INFO, TAG, "settings inflate hook: " + c.getName() + "#" + m.getName());
                    }
                    // x0(Ljava/lang/String;)Landroidx/preference/Preference; == findPreference(CharSequence)
                    if (ps.length == 1 && ps[0] == String.class && sFindPref == null
                            && m.getReturnType() != void.class && m.getReturnType().getName().startsWith("androidx.preference.")) {
                        sFindPref = m;
                        log(Log.INFO, TAG, "findPreference = " + c.getName() + "#" + m.getName() + " -> " + m.getReturnType().getName());
                    }
                }
            }
            log(Log.INFO, TAG, "settings hook installed, hooked=" + hooked + " findPref=" + sFindPref);
        } catch (Throwable t) { log(Log.ERROR, TAG, "hookSettings", t); }
    }

    private class ResourceHook implements XposedInterface.Hooker {
        public Object intercept(XposedInterface.Chain chain) {
            Object ret = chain.proceed();
            try {
                Object arg = chain.getArg(0);
                int resId = arg instanceof Integer ? ((Integer) arg).intValue() : 0;
                int want = 0;
                try { want = app().getResources().getIdentifier("basic_settings_preference", "xml", TARGET); } catch (Throwable t) { }
                log(Log.INFO, TAG, "settings xml inflated id=0x" + Integer.toHexString(resId) + " displayOptionsXml=0x" + Integer.toHexString(want));
                if (want != 0 && resId == want) injectDisplaySwitchR(chain.getThisObject());
            } catch (Throwable t) { log(Log.ERROR, TAG, "ResourceHook", t); }
            return ret;
        }
    }

    private static Class<?> sScreenCls, sGroupCls, sPrefCls, sSwCls;
    private static java.lang.reflect.Field sKeyField, sTitleField, sSummaryField, sWidgetField;
    private static int sSwitchLayoutId;
    private static Method sFindPref, sGetScreen, sGetChild, sCount, sGetKey, sSetKey, sGetTitle, sSetTitle, sGetSummary, sSetSummary;
    private static Method sSetListener, sAdd;
    private static Class<?> sListenerCls;
    private static final ArrayList<Method> sBoolSetters = new ArrayList<Method>();
    private static boolean sCheckedHooked;
    private static final ArrayList<Method> sAddCandidates = new ArrayList<Method>();

    private void injectDisplaySwitchR(Object fragment) {
        try {
            Object group = sFindPref == null ? null : sFindPref.invoke(fragment, "key_display_options");
            if (group == null) { log(Log.INFO, TAG, "display-options preference not found in " + fragment.getClass().getSimpleName()); return; }
            if (sPrefCls == null) initPrefApi(group);
            if (sGetChild == null || (sKeyField == null && sGetKey == null)) { log(Log.ERROR, TAG, "pref api unresolved"); return; }
            addOurSwitch(group, "amflyme_status_lyric", "状态栏歌词",
                    "在 Flyme 状态栏显示当前歌词（AMFlymeLyric 模块）", lyricEnabled());
            addOurSwitch(group, "amflyme_third_party", "第三方歌词源",
                    "官方没有滚动歌词时，用第三方词库补全（AMLL/QQ/酷狗/网易云，逐字优先）", thirdPartyEnabled());
        } catch (Throwable t) { log(Log.ERROR, TAG, "injectDisplaySwitchR", t); }
    }

    private void addOurSwitch(Object group, String key, String title, String summary, boolean on) {
        try {
            if (groupHasKey(group, key)) { log(Log.INFO, TAG, "switch already present: " + key); return; }
            Object sw = newSwitch();
            if (sw == null) { log(Log.ERROR, TAG, "cannot instantiate preference for " + key); return; }
            if (sKeyField != null) sKeyField.set(sw, key);
            else if (sSetKey != null) sSetKey.invoke(sw, key);
            if (sTitleField != null) sTitleField.set(sw, title);
            else if (sSetTitle != null) sSetTitle.invoke(sw, title);
            if (sSummaryField != null) sSummaryField.set(sw, summary);
            else if (sSetSummary != null) sSetSummary.invoke(sw, summary);
            if (sWidgetField != null && sSwitchLayoutId != 0) {
                try { sWidgetField.setInt(sw, sSwitchLayoutId); } catch (Throwable t) { }
            }
            for (int i = 0; i < sBoolSetters.size(); i++) {
                try { sBoolSetters.get(i).invoke(sw, Boolean.valueOf(on)); } catch (Throwable t) { }
            }
            if (sSetListener != null && sListenerCls != null) sSetListener.invoke(sw, newChangeListener());
            boolean added = false;
            for (int i = 0; i < sAddCandidates.size() && !added; i++) {
                Method add = sAddCandidates.get(i);
                int before = groupCount(group);
                try { add.invoke(group, sw); } catch (Throwable t) { }
                int after = groupCount(group);
                if (after > before) { added = true; sAdd = add; log(Log.INFO, TAG, "addPreference = " + add.getName() + " (" + before + " -> " + after + ")"); }
            }
            log(Log.INFO, TAG, "display-options switch '" + title + "' added=" + added + " checked=" + on);
        } catch (Throwable t) { log(Log.ERROR, TAG, "addOurSwitch " + key, t); }
    }

    private Object newChangeListener() {
        return Proxy.newProxyInstance(sCl, new Class[]{ sListenerCls }, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                try {
                    if (args != null && args.length == 2 && args[1] instanceof Boolean) {
                        setLyricEnabled(((Boolean) args[1]).booleanValue());
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) { log(Log.ERROR, TAG, "change listener", t); }
                return null;
            }
        });
    }

    private Object newSwitch() {
        try {
            Constructor<?>[] cs = sSwCls.getConstructors();
            for (int i = 0; i < cs.length; i++) {
                Class<?>[] ps = cs[i].getParameterTypes();
                if (ps.length == 0 || ps[0] != Context.class) continue;
                Object[] args = new Object[ps.length];
                args[0] = app();
                boolean ok = true;
                for (int j = 1; j < ps.length; j++) {
                    if (android.util.AttributeSet.class.isAssignableFrom(ps[j])) args[j] = null;
                    else if (ps[j] == int.class) args[j] = Integer.valueOf(0);
                    else { ok = false; break; }
                }
                if (!ok) continue;
                try { cs[i].setAccessible(true); Object o = cs[i].newInstance(args); log(Log.INFO, TAG, "switch ctor: " + cs[i]); return o; } catch (Throwable t) { }
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "newSwitch", t); }
        return null;
    }

    private static String keyOf(Object p) {
        try {
            if (sKeyField != null) { Object v = sKeyField.get(p); if (v instanceof String) return (String) v; }
            if (sGetKey != null) { Object v = sGetKey.invoke(p); if (v instanceof String) return (String) v; }
        } catch (Throwable t) { }
        return null;
    }

    private static boolean groupHasKey(Object group, String key) {
        try {
            int n = groupCount(group);
            for (int i = 0; i < n; i++) {
                Object p = sGetChild.invoke(group, i);
                Object k = p == null ? null : keyOf(p);
                if (key.equals(k)) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    private static Object findGroupByKey(Object node, String key, int depth) {
        if (node == null || depth > 4) return null;
        try {
            if (!sGroupCls.isInstance(node)) return null;
            Object myKey = keyOf(node);
            if (key.equals(myKey)) return node;
            int n = groupCount(node);
            for (int i = 0; i < n; i++) {
                Object r = findGroupByKey(sGetChild.invoke(node, i), key, depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable t) { }
        return null;
    }

    /** getPreferenceCount() 被 R8 删掉了，只能一直 getChild(i) 到越界。 */
    private static int groupCount(Object group) {
        int n = 0;
        for (int i = 0; i < 200; i++) {
            Object c = null;
            try { c = sGetChild.invoke(group, i); } catch (Throwable t) { break; }
            if (c == null) break;
            n++;
        }
        return n;
    }

    private static void collectPrefs(Object node, ArrayList<Object> out, int depth) {
        if (node == null || depth > 5 || out.size() > 400) return;
        out.add(node);
        try {
            if (!sGroupCls.isInstance(node)) return;
            int n = groupCount(node);
            for (int i = 0; i < n; i++) collectPrefs(sGetChild.invoke(node, i), out, depth + 1);
        } catch (Throwable t) { }
    }

    private void initPrefApi(Object screen) throws Throwable {
        // 注意：AM 里存在「被 XML 引用而保名的类」和「被 R8 改名的类」两套，不能直接用 loadClass 的
        // PreferenceGroup/Preference 去 instanceof 运行时对象——一切从运行时对象的继承链上认。
        log(Log.INFO, TAG, "runtime pref group class = " + screen.getClass().getName());
        sGroupCls = null;
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == int.class && m.getReturnType() != void.class
                        && m.getReturnType().getName().startsWith("androidx.preference.")) {
                    m.setAccessible(true);
                    sGetChild = m;
                    sPrefCls = m.getReturnType();
                    sGroupCls = c;
                    break;
                }
            }
            if (sGroupCls != null) break;
        }
        if (sGroupCls == null || sPrefCls == null) { log(Log.ERROR, TAG, "group/child api not found"); return; }
        log(Log.INFO, TAG, "groupCls=" + sGroupCls.getName() + " prefCls=" + sPrefCls.getName() + " getChild=" + sGetChild.getName());
        for (Class<?> c = sGroupCls; c != null && c != Object.class && sCount == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class) { m.setAccessible(true); sCount = m; break; }
            }
        }
        // 新建实例用的类：优先能渲染成开关的，其次 CheckBoxPreference（AM 自己也用它 + switch_preference_layout）
        // 用 CheckBoxPreference：AM 自己的「显示选项」开关就是它 + switch_preference_layout（SwitchPreferenceCompat 在 AM 主题下渲染不出开关）
        String[] swNames = { "androidx.preference.CheckBoxPreference", "androidx.preference.SwitchPreferenceCompat", "androidx.preference.SwitchPreference" };
        for (int i = 0; i < swNames.length && sSwCls == null; i++) {
            try {
                Class<?> c = sCl.loadClass(swNames[i]);
                if (sPrefCls.isAssignableFrom(c)) { sSwCls = c; log(Log.INFO, TAG, "switch class = " + c.getName() + " (assignable)"); }
                else log(Log.INFO, TAG, "switch class " + c.getName() + " NOT assignable to " + sPrefCls.getName());
            } catch (Throwable t) { log(Log.INFO, TAG, "switch class " + swNames[i] + " load failed"); }
        }
        if (sGetChild == null) { log(Log.ERROR, TAG, "getChild not found"); return; }
        ArrayList<Object> all = new ArrayList<Object>();
        collectPrefs(screen, all, 0);
        log(Log.INFO, TAG, "pref tree size=" + all.size());
        // 诊断：把运行时 Preference 上的 0 参 getter / 字段打出来（名字被混淆，只能看取值）
        try {
            Object p0 = all.get(0);
            int shown = 0;
            for (Class<?> c = sPrefCls; c != null && c != Object.class && shown < 40; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0 || shown >= 40) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt != String.class && rt != CharSequence.class && rt != boolean.class && rt != int.class) continue;
                    m.setAccessible(true);
                    Object v = null;
                    try { v = m.invoke(p0); } catch (Throwable t) { v = "<err>"; }
                    log(Log.INFO, TAG, "GET " + c.getSimpleName() + "#" + m.getName() + ":" + rt.getSimpleName() + " = " + v);
                    shown++;
                }
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (shown >= 40) break;
                    Class<?> ft = f.getType();
                    if (ft != String.class && ft != CharSequence.class && ft != boolean.class) continue;
                    f.setAccessible(true);
                    Object v = null;
                    try { v = f.get(p0); } catch (Throwable t) { v = "<err>"; }
                    log(Log.INFO, TAG, "FIELD " + c.getSimpleName() + "#" + f.getName() + ":" + ft.getSimpleName() + " = " + v);
                    shown++;
                }
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "diag", t); }
        // getKey()/getTitle()/setTitle() 这类方法可能被 R8 直接删掉（实测 getPreferenceCount、
        // getPreferenceScreen 都没了），所以 key/title/summary 直接按「已知取值」认字段，最稳。
        String catTitle = null, refTitle = null, refSummary = null;
        try {
            Application a = app();
            int id = a.getResources().getIdentifier("settings_display_options", "string", TARGET);
            int idc = a.getResources().getIdentifier("setting_increase_contrast_message", "string", TARGET);
            int idd = a.getResources().getIdentifier("setting_increase_contrast_description", "string", TARGET);
            if (id != 0) catTitle = a.getResources().getString(id);
            if (idc != 0) refTitle = a.getResources().getString(idc);
            if (idd != 0) refSummary = a.getResources().getString(idd);
        } catch (Throwable t) { }
        ArrayList<java.lang.reflect.Field> csFields = new ArrayList<java.lang.reflect.Field>();
        for (Class<?> c = sPrefCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    Object v = null;
                    try { v = f.get(screen); } catch (Throwable t) { }
                    if ("key_display_options".equals(v)) sKeyField = f;
                } else if (f.getType() == CharSequence.class) {
                    csFields.add(f);
                    Object v = null;
                    try { v = f.get(screen); } catch (Throwable t) { }
                    if (catTitle != null && catTitle.equals(v)) sTitleField = f;
                }
            }
        }
        for (int i = 0; i < csFields.size(); i++) {
            if (csFields.get(i) != sTitleField) { sSummaryField = csFields.get(i); break; }
        }
        // 再用「已知 child 的 title/summary」校一遍（category 的 summary 是 null，靠 child 区分更准）
        Object ref = null;
        for (int i = 0; i < all.size(); i++) {
            Object v = null;
            try { v = sKeyField == null ? null : sKeyField.get(all.get(i)); } catch (Throwable t) { }
            if ("key_increase_contrast".equals(v)) { ref = all.get(i); break; }
        }
        if (ref != null && refTitle != null) {
            for (int i = 0; i < csFields.size(); i++) {
                Object v = null;
                try { v = csFields.get(i).get(ref); } catch (Throwable t) { }
                if (refTitle.equals(v)) sTitleField = csFields.get(i);
                else if (refSummary != null && refSummary.equals(v)) sSummaryField = csFields.get(i);
            }
        }
        // 认 widgetLayout 字段：在「增强对比度」那行上，int 字段里等于 switch_preference_layout 的就是它
        try {
            Application a = app();
            sSwitchLayoutId = a.getResources().getIdentifier("switch_preference_layout", "layout", TARGET);
            if (ref != null && sSwitchLayoutId != 0) {
                for (Class<?> c = sPrefCls; c != null && c != Object.class && sWidgetField == null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (f.getType() != int.class) continue;
                        f.setAccessible(true);
                        try { if (f.getInt(ref) == sSwitchLayoutId) { sWidgetField = f; break; } } catch (Throwable t) { }
                    }
                }
            }
        } catch (Throwable t) { }
        log(Log.INFO, TAG, "field api: key=" + sKeyField + " title=" + sTitleField + " summary=" + sSummaryField
                + " widgetLayout=" + sWidgetField + " layoutId=0x" + Integer.toHexString(sSwitchLayoutId) + " (catTitle=" + catTitle + ")");
        for (Class<?> c = sSwCls; c != null && c != sPrefCls && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == boolean.class && m.getReturnType() == void.class) { m.setAccessible(true); sBoolSetters.add(m); }
            }
        }
        for (Method m : sPrefCls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || m.getReturnType() != void.class || !ps[0].isInterface()) continue;
            Method[] ims = ps[0].getDeclaredMethods();
            if (ims.length == 1 && ims[0].getReturnType() == boolean.class && ims[0].getParameterTypes().length == 2) { m.setAccessible(true); sSetListener = m; sListenerCls = ps[0]; break; }
        }
        for (Class<?> c = sGroupCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == sPrefCls && m.getReturnType() == void.class) { m.setAccessible(true); sAddCandidates.add(m); }
            }
        }
        log(Log.INFO, TAG, "pref api: keyField=" + sKeyField + " boolSetters=" + sBoolSetters + " listener=" + sSetListener + " addCandidates=" + sAddCandidates);
        // setChecked(boolean) 是唯一声明在 Preference 之下的 boolean setter —— 用户拨开关时会走到这里，
        // 用它当变更回调（setOnPreferenceChangeListener 被 R8 删掉了）。
        if (!sCheckedHooked && sBoolSetters.size() == 1) {
            sCheckedHooked = true;
            final Method setter = sBoolSetters.get(0);
            hookMethod(setter, new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    try {
                        Object a0 = chain.getArg(0);
                        if (a0 instanceof Boolean) {
                            boolean v = ((Boolean) a0).booleanValue();
                            String k = keyOf(chain.getThisObject());
                            if ("amflyme_status_lyric".equals(k)) {
                                log(Log.INFO, TAG, "status-lyric switch toggled -> " + v);
                                if (v != lyricEnabled()) setLyricEnabled(v);
                            } else if ("amflyme_third_party".equals(k)) {
                                log(Log.INFO, TAG, "third-party switch toggled -> " + v);
                                if (v != thirdPartyEnabled()) setThirdPartyEnabled(v);
                            }
                        }
                    } catch (Throwable t) { log(Log.ERROR, TAG, "checked hook", t); }
                    return ret;
                }
            });
            log(Log.INFO, TAG, "hooked bool setter for change callback: " + setter);
        }
    }

    private static final String[] DISPLAY_KEYS = {
        "key_display_options", "key_theme_mode", "key_increase_contrast",
        "key_lyrics_screen", "key_motion_screen", "key_playback_show_lockscreen_art"
    };

    private void injectDisplaySwitch(Object screen) {
        if (screen == null) return;
        try {
            Object group = findDisplayGroup(screen, 0);
            if (group == null) return;
            if (hasOurSwitch(group)) return;
            addOurSwitch(group);
        } catch (Throwable t) { log(Log.ERROR, TAG, "injectDisplaySwitch", t); }
    }

    /** 找「显示选项」分组：优先 key==key_display_options 的分组，其次包含显示选项子键的分组。 */
    private Object findDisplayGroup(Object node, int depth) {
        if (node == null || depth > 3) return null;
        try {
            Class<?> groupCls = sCl.loadClass("androidx.preference.PreferenceGroup");
            if (!groupCls.isInstance(node)) return null;
            Class<?> prefCls = sCl.loadClass("androidx.preference.Preference");
            Method gc = groupCls.getMethod("getPreferenceCount");
            Method gp = groupCls.getMethod("getPreference", int.class);
            Method gk = prefCls.getMethod("getKey");
            if ("key_display_options".equals((String) gk.invoke(node))) return node;
            int n = ((Integer) gc.invoke(node)).intValue();
            boolean looksLikeDisplay = false;
            for (int i = 0; i < n; i++) {
                Object p = gp.invoke(node, i);
                if (p == null) continue;
                String k = (String) gk.invoke(p);
                if (k != null) {
                    for (int j = 1; j < DISPLAY_KEYS.length; j++) if (DISPLAY_KEYS[j].equals(k)) looksLikeDisplay = true;
                }
                if (groupCls.isInstance(p)) {
                    Object r = findDisplayGroup(p, depth + 1);
                    if (r != null) return r;
                }
            }
            return looksLikeDisplay ? node : null;
        } catch (Throwable t) { return null; }
    }

    private boolean hasOurSwitch(Object group) {
        try {
            Class<?> groupCls = sCl.loadClass("androidx.preference.PreferenceGroup");
            Method gc = groupCls.getMethod("getPreferenceCount");
            Method gp = groupCls.getMethod("getPreference", int.class);
            Method gk = sCl.loadClass("androidx.preference.Preference").getMethod("getKey");
            int n = ((Integer) gc.invoke(group)).intValue();
            for (int i = 0; i < n; i++) {
                Object p = gp.invoke(group, i);
                if (p != null && "amflyme_status_lyric".equals((String) gk.invoke(p))) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    private void addOurSwitch(Object group) {
        try {
            final Application a = app();
            if (a == null) return;
            Class<?> prefCls = sCl.loadClass("androidx.preference.Preference");
            Class<?> groupCls = sCl.loadClass("androidx.preference.PreferenceGroup");
            Class<?> cbCls = sCl.loadClass("androidx.preference.CheckBoxPreference");   // AM 的显示选项用的也是它
            Object sw = cbCls.getConstructor(Context.class).newInstance(a);
            prefCls.getMethod("setKey", String.class).invoke(sw, "amflyme_status_lyric");
            prefCls.getMethod("setTitle", CharSequence.class).invoke(sw, "状态栏歌词");
            prefCls.getMethod("setSummary", CharSequence.class).invoke(sw, "在 Flyme 状态栏显示当前歌词（AMFlymeLyric 模块）");
            prefCls.getMethod("setPersistent", boolean.class).invoke(sw, Boolean.FALSE);
            int layout = a.getResources().getIdentifier("switch_preference_layout", "layout", a.getPackageName());
            if (layout != 0) prefCls.getMethod("setWidgetLayoutResource", int.class).invoke(sw, Integer.valueOf(layout));
            cbCls.getMethod("setChecked", boolean.class).invoke(sw, Boolean.valueOf(lyricEnabled()));
            Class<?> lCls = sCl.loadClass("androidx.preference.Preference$OnPreferenceChangeListener");
            Object listener = Proxy.newProxyInstance(sCl, new Class[]{ lCls }, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        if ("onPreferenceChange".equals(method.getName()) && args != null && args.length == 2 && args[1] instanceof Boolean) {
                            setLyricEnabled(((Boolean) args[1]).booleanValue());
                            return Boolean.TRUE;
                        }
                    } catch (Throwable t) { log(Log.ERROR, TAG, "switch listener", t); }
                    return null;
                }
            });
            prefCls.getMethod("setOnPreferenceChangeListener", lCls).invoke(sw, listener);
            groupCls.getMethod("addPreference", prefCls).invoke(group, sw);
            log(Log.INFO, TAG, "added 状态栏歌词 switch to Display Options, checked=" + lyricEnabled());
        } catch (Throwable t) { log(Log.ERROR, TAG, "addOurSwitch", t); }
    }

    private boolean lyricEnabled() {
        Boolean v = sLyricEnabled;
        if (v != null) return v.booleanValue();
        boolean on = true;
        try {
            Application a = app();
            if (a != null) on = a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_STATUS_LYRIC, true);
        } catch (Throwable t) { }
        sLyricEnabled = Boolean.valueOf(on);
        return on;
    }

    private void setLyricEnabled(boolean on) {
        sLyricEnabled = Boolean.valueOf(on);
        try {
            Application a = app();
            if (a != null) {
                a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_STATUS_LYRIC, on).apply();
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "save pref", t); }
        log(Log.INFO, TAG, "status bar lyric enabled=" + on);
        if (!on) {
            sTickerText = null;
            clearTicker();
        } else {
            String t = sLastLyric;
            if (t != null) pushTicker(t);
        }
    }

    /** 关掉开关时把之前写进通知的 ticker 信息清掉，状态栏立刻恢复时钟。 */
    private void clearTicker() {
        try {
            Application a = app();
            if (a == null) return;
            ArrayList<NotifRef> refs;
            synchronized (sNotifs) { refs = new ArrayList<NotifRef>(sNotifs); }
            NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
            for (int i = 0; i < refs.size(); i++) {
                NotifRef r = refs.get(i);
                try {
                    Notification copy = r.n.clone();
                    copy.tickerText = null;
                    copy.flags &= ~(FLAG_ALWAYS_SHOW_TICKER | FLAG_ONLY_UPDATE_TICKER);
                    if (copy.extras != null) {
                        copy.extras.remove("ticker_icon");
                        copy.extras.putBoolean("ticker_icon_switch", false);
                    }
                    if (r.tag == null) nm.notify(r.id, copy); else nm.notify(r.tag, r.id, copy);
                } catch (Throwable t) { log(Log.ERROR, TAG, "clearTicker id=" + r.id, t); }
            }
            log(Log.INFO, TAG, "ticker cleared");
        } catch (Throwable t) { log(Log.ERROR, TAG, "clearTicker", t); }
    }

    // ------------------------------------------------ third-party lyric source

    private static boolean isInstrumentalTitle(String title) {
        if (title == null) return false;
        String t = title.toLowerCase(java.util.Locale.ROOT);
        String[] keys = {"instrumental", "karaoke", "伴奏", "纯音乐", "off vocal", "offvocal", "inst.", "(inst)"};
        for (int i = 0; i < keys.length; i++) if (t.contains(keys[i])) return true;
        return false;
    }

    /** mark the item AM is about to load lyrics for as "has synced lyrics" so its UI keeps the lyric page */
    private void prepareThirdPartyItem(Object item) {
        if (!thirdPartyEnabled()) return;
        invokeArgs(item, "setHasLyrics", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
        invokeArgs(item, "setHasTimeSyncedLyrics", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
        if (sThirdCachePath != null) {
            invokeArgs(item, "setOfflineLyricsFilePath", new Class[]{String.class}, new Object[]{sThirdCachePath});
        }
        log(Log.INFO, TAG, "prepared item for third-party lyrics, cache=" + sThirdCachePath);
    }

    private boolean thirdPartyEnabled() {
        Boolean v = sThirdOn;
        if (v != null) return v.booleanValue();
        boolean on = false;
        try {
            Application a = app();
            if (a != null) on = a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_THIRD_PARTY, false);
        } catch (Throwable t) { }
        sThirdOn = Boolean.valueOf(on);
        return on;
    }

    private void setThirdPartyEnabled(boolean on) {
        sThirdOn = Boolean.valueOf(on);
        sThirdFailed.clear();
        try {
            Application a = app();
            if (a != null) a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_THIRD_PARTY, on).apply();
        } catch (Throwable t) { log(Log.ERROR, TAG, "save third-party pref", t); }
        log(Log.INFO, TAG, "third-party lyric source enabled=" + on);
        if (on) {
            if (sTrackId != null) thirdOnTrack(sTrackId, sCurrentTitle);
        } else {
            sThirdGen++;
            sThirdTtml = null;
            sThirdLines = 0;
            sThirdFetching = false;
            // let AM reload official lyrics
            if (sTrackId != null) requestLyrics(sTrackId);
        }
    }

    /** called from tick() when the playing track changes */
    private void thirdOnTrack(String id, String title) {
        if (!thirdPartyEnabled() || id == null) return;
        // same track already handled (or being fetched): do not restart the whole thing
        if (id.equals(sThirdTrackId) && (sThirdFetching || sThirdTtml != null)) return;
        if (sThirdFailed.contains(id)) {
            log(Log.INFO, TAG, "third-party: no match found earlier for " + id + ", skip");
            return;
        }
        if (isInstrumentalTitle(title)) {
            sThirdTrackId = id;
            sThirdTtml = null;
            sThirdLines = 0;
            log(Log.INFO, TAG, "third-party: instrumental/karaoke track, skip ({}) " + title);
            return;
        }
        sThirdGen++;
        sThirdTtml = null;
        sThirdLines = 0;
        sThirdTrackId = id;
        sThirdFetching = false;
        sThirdScheduled = false;
        sOfficialTimed = false;
        sCurrentTitle = title == null ? "" : title;
        String cache = thirdCachePath(id);
        sThirdCachePath = cache;
        String cached = readText(cache);
        if (cached != null && cached.length() > 0) {
            log(Log.INFO, TAG, "third-party cache hit for " + id + " (" + cached.length() + " bytes)");
            if (applyThirdTtml(id, cached, "cache")) return;
            log(Log.INFO, TAG, "third-party: cached TTML rejected by AM, refetching");
            if (cache != null) { try { new File(cache).delete(); } catch (Throwable t) { } }
        }
        if (sCurrentTitle.length() == 0) return;
        sThirdScheduled = true;
        scheduleThirdFetch(sThirdGen, id, 2200);
    }

    private void scheduleThirdFetch(final int gen, final String id, final long delayMs) {
        Thread th = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
                if (gen != sThirdGen) return;
                if (sOfficialTimed) { log(Log.INFO, TAG, "third-party: official timed lyrics present, skip"); return; }
                if (!thirdPartyEnabled()) return;
                fetchThirdLyrics(gen, id);
            }
        }, "AMFlymeLyric-third-delay");
        th.setDaemon(true);
        th.start();
    }

    private void fetchThirdLyrics(final int gen, final String id) {
        if (sThirdFetching) return;
        sThirdScheduled = false;
        try {
            ThirdPartyLyrics.setCacheDir(thirdCacheDir());
            ThirdPartyLyrics.setLogger(new ThirdPartyLyrics.Logger() {
                public void log(String message) { AmLyricModule.this.log(Log.INFO, TAG, message); }
            });
        } catch (Throwable t) { }
        final ThirdPartyLyrics.Track t = new ThirdPartyLyrics.Track();
        t.id = id;
        t.title = sCurrentTitle;
        t.artist = sTrackArtist;
        t.album = sTrackAlbum;
        t.durationMs = sTrackDuration;
        sThirdFetching = true;
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    log(Log.INFO, TAG, "third-party search: " + t);
                    ThirdPartyLyrics.Result r = ThirdPartyLyrics.resolve(t);
                    if (gen != sThirdGen) { log(Log.INFO, TAG, "third-party: discard (track changed)"); return; }
                    if (sOfficialTimed) { log(Log.INFO, TAG, "third-party: discard (official timed lyrics)"); return; }
                    log(Log.INFO, TAG, "third-party: winner " + r);
                    applyThirdResult(id, r);
                } catch (Throwable ex) {
                    log(Log.ERROR, TAG, "third-party fetch failed: " + ex);
                    sThirdFailed.add(id);
                } finally {
                    sThirdFetching = false;
                }
            }
        }, "AMFlymeLyric-third");
        th.setDaemon(true);
        th.start();
    }

    /** official TTML arrived but has no timing -> start our fetch right away */
    private void thirdOfficialUntimed() {
        if (!thirdPartyEnabled()) return;
        if (sOfficialTimed) return;
        if (sThirdScheduled || sThirdFetching || sThirdTtml != null) return;
        if (sThirdTrackId != null && sThirdFailed.contains(sThirdTrackId)) return;
        String id = sThirdTrackId;
        if (id == null || !id.equals(sTrackId)) return;
        sThirdScheduled = true;
        log(Log.INFO, TAG, "third-party: official lyrics untimed, fetching");
        fetchThirdLyrics(sThirdGen, id);
    }

    /** validate the generated TTML with Apple's own native parser, then use it */
    private void applyThirdResult(String id, ThirdPartyLyrics.Result r) {
        if (r == null) return;
        if (applyThirdTtml(id, r.ttml, r.toString())) {
            String path = thirdCachePath(id);
            if (path != null) writeText(path, r.ttml);
            return;
        }
        if (r.ttmlLine != null && r.ttmlLine.length() > 0 && !r.ttmlLine.equals(r.ttml)) {
            log(Log.INFO, TAG, "third-party: word-level TTML rejected, falling back to line-level");
            if (applyThirdTtml(id, r.ttmlLine, r.toString() + " (line)")) {
                String path = thirdCachePath(id);
                if (path != null) writeText(path, r.ttmlLine);
            }
        }
    }

    private boolean applyThirdTtml(String id, String ttml, String src) {
        if (ttml == null || ttml.length() == 0) return false;
        if (id != null && !id.equals(sThirdTrackId)) return false;
        if (parseTtmlPtr(ttml) == null) {
            log(Log.ERROR, TAG, "third-party TTML rejected by AM native parser (" + src + ")");
            return false;
        }
        sThirdTtml = ttml;
        applyLines(ttml);
        sThirdLines = sTexts.length;
        log(Log.INFO, TAG, "third-party lyrics ready (" + src + ") lines=" + sThirdLines);
        pushCurrentLyric();
        for (int i = 0; i < 3; i++) {
            final long delay = i == 0 ? 0L : (i == 1 ? 900L : 2600L);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    try { injectThirdIntoRealVm(); } catch (Throwable t) { log(Log.ERROR, TAG, "injectThird", t); }
                }
            }, delay);
        }
        return true;
    }

    private void applyLines(String xml) {
        try {
            ArrayList<long[]> bl = new ArrayList<long[]>();
            ArrayList<String> tl = new ArrayList<String>();
            scanParagraphs(xml, bl, tl);
            int n = bl.size();
            long[] b = new long[n];
            long[] e = new long[n];
            String[] s = new String[n];
            for (int i = 0; i < n; i++) {
                b[i] = bl.get(i)[0];
                e[i] = bl.get(i)[1];
                s[i] = tl.get(i);
            }
            synchronized (this) {
                sBegins = b;
                sEnds = e;
                sTexts = s;
                sCurLine = -2;
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "applyLines", t); }
    }

    private void pushCurrentLyric() {
        try {
            long pos = num(invoke(sPlayer, "getCurrentPosition"), -1);
            long[] b = sBegins;
            String[] s = sTexts;
            if (b.length == 0 || pos < 0) return;
            int line = -1;
            for (int i = 0; i < b.length; i++) { if (pos >= b[i]) line = i; else break; }
            if (line >= 0 && line < s.length && s[line] != null) pushTicker(s[line]);
        } catch (Throwable t) { }
    }

    /** build a SongInfoPtr from our TTML through Apple's own native parser */
    private Object parseTtmlPtr(String ttml) {
        try {
            Class<?> p = sCl.loadClass("com.apple.android.music.ttml.javanative.TTMLParser$TTMLParserNative");
            Object parser = p.getConstructor().newInstance();
            Method m = p.getDeclaredMethod("songInfoFromTTML", String.class);
            sSelfTtmlCall = true;
            try { return m.invoke(parser, ttml); } finally { sSelfTtmlCall = false; }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "parseTtmlPtr", t);
            return null;
        }
    }

    private Object newPair(Object a, Object b) {
        try {
            Class<?> c = sCl.loadClass("kotlin.Pair");
            return c.getConstructor(Object.class, Object.class).newInstance(a, b);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "newPair", t);
            return null;
        }
    }

    /** push our lyrics into the real lyric page's view model (mLyricsResult) */
    private void injectThirdIntoRealVm() {
        Object vm = sRealVm;
        String ttml = sThirdTtml;
        if (vm == null || ttml == null) return;
        if (!thirdPartyEnabled() || sOfficialTimed) return;
        String key = String.valueOf(sThirdTrackId) + "#" + sThirdLines;
        if (key.equals(sInjectedKey)) return;
        Object ptr = parseTtmlPtr(ttml);
        if (ptr == null) { log(Log.ERROR, TAG, "injectThird: native parser returned null"); return; }
        Object item = sRealItem;
        try {
            if (item != null) {
                invokeArgs(item, "setHasLyrics", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
                invokeArgs(item, "setHasTimeSyncedLyrics", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
                if (sThirdCachePath != null) invokeArgs(item, "setOfflineLyricsFilePath", new Class[]{String.class}, new Object[]{sThirdCachePath});
            }
        } catch (Throwable t) { }
        try {
            Object lv = getField(vm, "mLyricsResult");
            Object pair = newPair(ptr, null);
            if (lv != null && pair != null) {
                invokeArgs(lv, "postValue", new Class[]{Object.class}, new Object[]{pair});
            }
        } catch (Throwable t) { log(Log.ERROR, TAG, "injectThird postValue", t); }
        try {
            Method[] ms = vm.getClass().getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!"buildTimeRangeToLyricsMap".equals(ms[i].getName())) continue;
                if (ms[i].getParameterTypes().length != 1) continue;
                ms[i].setAccessible(true);
                ms[i].invoke(vm, ptr);
                break;
            }
        } catch (Throwable t) { }
        sInjectedKey = key;
        log(Log.INFO, TAG, "injected third-party lyrics into player view model, lines=" + sThirdLines);
    }

    private static Object invokeArgs(Object target, String name, Class[] types, Object[] args) {
        if (target == null) return null;
        Class c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private String thirdCacheDir() {
        try {
            Application a = app();
            if (a == null) return null;
            File d = new File(a.getFilesDir(), "amlyric-third");
            if (!d.exists()) d.mkdirs();
            return d.getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    private String thirdCachePath(String id) {
        try {
            String dir = thirdCacheDir();
            if (dir == null || id == null) return null;
            String safe = id.replaceAll("[^A-Za-z0-9_.-]", "_");
            return new File(dir, safe + ".ttml").getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    private static String readText(String path) {
        if (path == null) return null;
        FileInputStream in = null;
        try {
            File f = new File(path);
            if (!f.exists() || f.length() <= 0) return null;
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) { }
        }
    }

    private void writeText(String path, String content) {
        if (path == null || content == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(path);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "writeText " + path, t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable t) { }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static long num(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        return def;
    }

    private static Object invoke(Object target, String name) {
        if (target == null) return null;
        Class c = target.getClass();
        while (c != null) {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == 0) {
                    try { ms[i].setAccessible(true); return ms[i].invoke(target, new Object[0]); } catch (Throwable t) { return null; }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private Application app() {
        Application a = sApp;
        if (a != null) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication", new Class[0]);
            m.setAccessible(true);
            a = (Application) m.invoke(null, new Object[0]);
            sApp = a;
        } catch (Throwable t) { log(Log.ERROR, TAG, "app()", t); }
        return a;
    }

    private void dump(String name, String content) {
        if (content == null) return;
        try {
            Application a = app();
            if (a == null) return;
            File dir = new File(a.getExternalFilesDir(null), "amlyric");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            log(Log.INFO, TAG, "dumped " + f.getAbsolutePath() + " (" + content.length() + ")");
        } catch (Throwable t) { log(Log.ERROR, TAG, "dump " + name, t); }
    }
}
