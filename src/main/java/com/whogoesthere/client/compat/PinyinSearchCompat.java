package com.whogoesthere.client.compat;

import com.whogoesthere.WhoGoesThere;
import java.lang.reflect.Method;
import java.util.Locale;
import net.neoforged.fml.ModList;

/**
 * 与 JustEnoughCharacters（modid: {@value #JECH_MOD_ID}）的**软联动**。
 *
 * <p>拼音匹配这件事我们不自研：JECh 已经把拼音引擎（PinIn）连同
 * {@code me.towdium.jecharacters.utils.Match} 一起发布，所有中文模组的搜索都走它。
 * 这里只做两件事：
 * <ol>
 *   <li>检查 JECh 在不在；</li>
 *   <li>反射调用 {@code Match.contains(String 名字, CharSequence 查询)}。</li>
 * </ol>
 *
 * <p>全程反射、失败即降级：JECh 没装、内部改名、调用抛异常，都只是退回普通
 * {@code contains}，绝不让本模组崩溃或加载失败。
 */
public final class PinyinSearchCompat {

    private static final String JECH_MOD_ID = "jecharacters";
    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    private static boolean initialised = false;
    /** JECh 的拼音匹配方法；为 null 表示不可用（走普通匹配）。 */
    private static Method jechContains = null;

    private PinyinSearchCompat() {
    }

    private static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        if (!ModList.get().isLoaded(JECH_MOD_ID)) {
            WhoGoesThere.LOGGER.info("[谁在那！] 未检测到 JustEnoughCharacters —— 名字过滤使用普通匹配"
                    + "（装上 JECh 即可解锁拼音/首字母搜索）");
            return;
        }

        try {
            Method method = Class.forName(MATCH_CLASS).getMethod("contains", String.class, CharSequence.class);
            method.setAccessible(true);
            jechContains = method;
            WhoGoesThere.LOGGER.info("[谁在那！] 已联动 JustEnoughCharacters —— 拼音与首字母搜索可用 ✅");
        } catch (Throwable t) {
            jechContains = null;
            WhoGoesThere.LOGGER.warn("[谁在那！] 检测到 JECh 但联动失败，退回普通匹配：{}", t.toString());
        }
    }

    /** 供 UI 展示：当前是否真在用拼音引擎。 */
    public static boolean pinyinActive() {
        init();
        return jechContains != null;
    }

    /**
     * 名字是否命中查询词。
     *
     * @param name  实体的显示名（已 {@code getString()}）
     * @param query 用户输入（任意大小写）
     */
    public static boolean matches(String name, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = name == null ? "" : name;
        String needle = query.trim();

        // 1) 普通匹配 —— 保证英文/ID 搜索在任何情况下都正常
        if (haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
            return true;
        }

        // 2) 拼音匹配 —— 命中「僵尸 → jiangshi / js」这类
        init();
        if (jechContains != null) {
            try {
                Object result = jechContains.invoke(null, haystack, needle);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 单次调用失败不影响整体；下面直接返回 false
            }
        }
        return false;
    }
}
