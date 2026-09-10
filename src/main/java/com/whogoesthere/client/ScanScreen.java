package com.whogoesthere.client;

import com.whogoesthere.client.compat.PinyinSearchCompat;
import com.whogoesthere.network.payload.HighlightRequestPayload;
import com.whogoesthere.network.payload.ScanResultPayload.EntityInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 仅客户端：「谁在那！」结果面板。
 *
 * <p>上面一个搜索框，下面一个可滚动列表。搜索框支持 {@code @} 语法筛选模组
 * （{@code @apo} / {@code @apo 僵尸}），正在输入 {@code @} 词时下方会浮出候选模组，
 * 点击或按 Tab 即可补全。</p>
 *
 * <p>点某一行就请服务端给它打发光标记，关掉界面并在聊天栏里报出它的坐标——
 * 那条消息本身还能点：点主体把 {@code /tp} 填进聊天栏，点 {@code [直接传送]} 直接执行。</p>
 */
public class ScanScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_MARGIN = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HOVER = 0xFFFFE08A;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF8CC8FF;

    /** 补全浮层最多列几个候选。 */
    private static final int MAX_COMPLETIONS = 8;
    /** 补全浮层每行高度（比结果行紧凑）。 */
    private static final int COMPLETION_ROW_HEIGHT = 12;
    /** 浮层配色，参照原版 tooltip 的深底 + 亮边。 */
    private static final int COLOR_POPUP_BG = 0xF0100010;
    private static final int COLOR_POPUP_BORDER = 0xFF5050FF;
    private static final int COLOR_POPUP_HOVER = 0x50FFFFFF;

    /** 模组显示名缓存：setEntries 之后每帧都会画行，别每帧去翻 ModList。 */
    private static final Map<String, String> MOD_NAME_CACHE = new HashMap<>();

    private final List<EntityInfo> allEntries;
    /** 本次结果里实际出现过的命名空间，去重 + 排序 —— 也是补全候选的来源。 */
    private final List<String> namespaces;
    private List<EntityInfo> filtered;
    private String filterText = "";

    /** 当前查询切出来的词，补全统计时要用它排除「正在输入的那个词」。 */
    private List<Token> tokens = List.of();

    private EditBox searchBox;
    private ResultList resultList;
    private int listTop;
    private int listHeight;

    /** 光标处正在输入的 @ 词（含前导 @）；null = 当前没有补全上下文。 */
    private String completionWord;
    private int completionWordStart;
    private int completionWordEnd;
    /** 排好序的候选（最多 {@value #MAX_COMPLETIONS} 个）。 */
    private final List<Candidate> completions = new ArrayList<>();
    /** 缓存键「光标 + 文本」：文本/光标没变就不重算，省得每帧扫一遍名单。 */
    private String completionKey;

    public ScanScreen(List<EntityInfo> entries) {
        super(Component.translatable("gui.whogoesthere.title"));
        this.allEntries = List.copyOf(entries);

        TreeSet<String> found = new TreeSet<>();
        for (EntityInfo entry : entries) {
            found.add(entry.namespace());
        }
        this.namespaces = List.copyOf(found);

        this.filtered = List.copyOf(entries);
    }

    @Override
    protected void init() {
        this.listTop = 56;
        this.listHeight = Math.max(ROW_HEIGHT * 2, this.height - this.listTop - 32);

        this.resultList = new ResultList(this.minecraft, this.width - LIST_MARGIN * 2, this.listHeight, this.listTop, ROW_HEIGHT);
        this.resultList.setX(LIST_MARGIN);
        this.addRenderableWidget(this.resultList);

        // 搜索框现在占满整行 —— 模组筛选搬进了 @ 语法，不再需要那颗循环按钮
        this.searchBox = new SearchBox(this.font, LIST_MARGIN, 28, Math.max(80, this.width - LIST_MARGIN * 2), 20,
                Component.translatable("gui.whogoesthere.search"));
        this.searchBox.setHint(Component.translatable("gui.whogoesthere.search.hint"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::applyFilter);
        this.searchBox.setValue(this.filterText);
        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.applyFilter(this.filterText);
    }

    // ------------------------------------------------------------------
    // 查询解析
    // ------------------------------------------------------------------

    /** 一个空白分隔的词。{@code mod} 表示它以 {@code @} 开头。 */
    private record Token(String text, boolean mod, int start, int end) {
        /** @ 后面的内容（去掉 @、转小写）；非 @ 词返回 null。 */
        String modPrefix() {
            return this.mod ? this.text.substring(1).toLowerCase(Locale.ROOT) : null;
        }

        /** 光写了个 @ 还没写名字 —— 这种当「不限制」处理。 */
        boolean blankMod() {
            return this.mod && this.text.length() <= 1;
        }
    }

    /** 按空白把查询切成词，顺便记下每个词在原文里的区间（补全替换要用）。 */
    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            String word = text.substring(start, i);
            tokens.add(new Token(word, word.startsWith("@"), start, i));
        }
        return tokens;
    }

    /**
     * 一条结果是否满足所有词。
     *
     * <p>模组词（{@code @xx}）和名字词各自都是 AND：{@code @apo 僵尸} 就是
     * 「属于 Apotheosis」且「名字命中 僵尸」。{@code skip} 用来在补全时跳过正在输入的那个词。</p>
     */
    private static boolean matchesTokens(EntityInfo entry, List<Token> tokens, Token skip) {
        for (Token token : tokens) {
            if (token == skip || token.blankMod()) {
                continue;
            }
            if (token.mod()) {
                if (!modMatches(entry.namespace(), token.modPrefix())) {
                    return false;
                }
            } else if (!matchesQuery(entry, token.text().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** 模组词是否命中：命名空间或模组显示名，大小写不敏感，子串即可。 */
    private static boolean modMatches(String namespace, String prefix) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (namespace.toLowerCase(Locale.ROOT).contains(prefix)) {
            return true;
        }
        return modDisplayName(namespace).toLowerCase(Locale.ROOT).contains(prefix);
    }

    private void applyFilter(String rawQuery) {
        this.filterText = rawQuery == null ? "" : rawQuery;
        this.tokens = tokenize(this.filterText);

        List<EntityInfo> matches = new ArrayList<>();
        for (EntityInfo entry : this.allEntries) {
            if (matchesTokens(entry, this.tokens, null)) {
                matches.add(entry);
            }
        }
        this.filtered = matches;
        if (this.resultList != null) {
            this.resultList.setEntries(matches);
        }

        // 文本变了，补全必须重算
        this.completionKey = null;
        this.refreshCompletions();
    }

    /**
     * 一条结果是否命中搜索词。
     *
     * <p>名字走 {@link PinyinSearchCompat}（装了 JECh 就是拼音/首字母匹配），
     * 再补注册 id（{@code zombie} / {@code minecraft:diamond_sword}）和命名空间匹配。
     * 掉落物额外用 {@code ItemStack#getHoverName()} 的字符串再走一遍拼音。</p>
     */
    private static boolean matchesQuery(EntityInfo entry, String query) {
        if (PinyinSearchCompat.matches(entry.name().getString(), query)) {
            return true;
        }
        if (entry.typeId().toString().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (entry.namespace().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return entry.isItem() && PinyinSearchCompat.matches(entry.stack().getHoverName().getString(), query);
    }

    // ------------------------------------------------------------------
    // @ 补全
    // ------------------------------------------------------------------

    /** 一个候选模组。 */
    private record Candidate(String namespace, String displayName, int count, int score) {
    }

    /** 候选排序分：0 = 命名空间/显示名前缀命中，1 = 仅子串命中，-1 = 不命中。 */
    private static int modScore(String namespace, String prefix) {
        if (prefix.isEmpty()) {
            return 0;
        }
        String ns = namespace.toLowerCase(Locale.ROOT);
        String name = modDisplayName(namespace).toLowerCase(Locale.ROOT);
        if (ns.startsWith(prefix) || name.startsWith(prefix)) {
            return 0;
        }
        if (ns.contains(prefix) || name.contains(prefix)) {
            return 1;
        }
        return -1;
    }

    /**
     * 重算补全候选。只在「光标停在某个 {@code @} 词末尾」时才有货；
     * 缓存键没变就直接复用上一次的结果（每帧都会调一次，别无脑重扫）。
     */
    private void refreshCompletions() {
        if (this.searchBox == null) {
            this.completions.clear();
            this.completionWord = null;
            return;
        }

        String text = this.searchBox.getValue();
        int cursor = this.searchBox.getCursorPosition();
        String key = cursor + "\u0000" + text;
        if (key.equals(this.completionKey)) {
            return;
        }
        this.completionKey = key;
        this.completions.clear();
        this.completionWord = null;

        if (!this.searchBox.isFocused()) {
            return;
        }

        // 1) 光标必须停在词的末尾（词 = 光标前后那一段非空白）
        int start = cursor;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        int end = cursor;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        if (cursor != end) {
            return; // 光标卡在词中间，先别打扰
        }
        String word = text.substring(start, end);
        if (!word.startsWith("@")) {
            return;
        }
        String prefix = word.substring(1).toLowerCase(Locale.ROOT);

        // 2) 找到这个词对应的 token，统计条数时把它排除掉 —— 这样
        //    「@apo」时 Apotheosis 显示的是加入它之后能拿到多少条，而不是 0
        Token active = null;
        for (Token token : this.tokens) {
            if (token.start() == start && token.end() == end) {
                active = token;
                break;
            }
        }

        List<Candidate> found = new ArrayList<>();
        for (String ns : this.namespaces) {
            int score = modScore(ns, prefix);
            if (score < 0) {
                continue;
            }
            int count = 0;
            for (EntityInfo entry : this.allEntries) {
                if (entry.namespace().equals(ns) && matchesTokens(entry, this.tokens, active)) {
                    count++;
                }
            }
            found.add(new Candidate(ns, modDisplayName(ns), count, score));
        }

        found.sort(Comparator
                .comparingInt(Candidate::score)
                .thenComparing(candidate -> -candidate.count())
                .thenComparing(Candidate::namespace));

        // 3) 已经精确命中唯一模组（@apotheosis）就别再浮一层挡视线
        if (found.size() == 1) {
            Candidate only = found.get(0);
            if (only.namespace().equalsIgnoreCase(prefix) || only.displayName().equalsIgnoreCase(prefix)) {
                return;
            }
        }

        for (int i = 0; i < Math.min(MAX_COMPLETIONS, found.size()); i++) {
            this.completions.add(found.get(i));
        }
        if (!this.completions.isEmpty()) {
            this.completionWord = word;
            this.completionWordStart = start;
            this.completionWordEnd = end;
        }
    }

    /** 采纳第 {@code index} 个候选：只替换当前那个 @ 词，并补一个空格方便接着输名字。 */
    private void acceptCompletion(int index) {
        if (index < 0 || index >= this.completions.size() || this.completionWord == null || this.searchBox == null) {
            return;
        }
        Candidate pick = this.completions.get(index);
        String text = this.searchBox.getValue();
        int start = Math.max(0, Math.min(this.completionWordStart, text.length()));
        int end = Math.max(start, Math.min(this.completionWordEnd, text.length()));

        String replaced = text.substring(0, start) + "@" + pick.namespace() + " " + text.substring(end);
        this.searchBox.setValue(replaced); // setValue 会触发 responder -> applyFilter
        this.searchBox.setCursorPosition(start + 1 + pick.namespace().length() + 1);
        this.searchBox.setHighlightPos(this.searchBox.getCursorPosition());

        this.completions.clear();
        this.completionWord = null;
        this.completionKey = null;
    }

    /** Tab：补全到第一个候选；没有候选就当作没按过，让按键继续往下走。 */
    private boolean acceptFirstCompletion() {
        if (this.completions.isEmpty()) {
            return false;
        }
        this.acceptCompletion(0);
        return true;
    }

    /** 浮层里第 {@code index} 行是否被鼠标指着。 */
    private boolean isOverCompletion(double mouseX, double mouseY, int index) {
        int x = this.searchBox.getX();
        int y = this.searchBox.getY() + this.searchBox.getHeight() + 2 + index * COMPLETION_ROW_HEIGHT;
        return mouseX >= x && mouseX < x + this.searchBox.getWidth()
                && mouseY >= y && mouseY < y + COMPLETION_ROW_HEIGHT;
    }

    private void renderCompletions(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.completions.isEmpty()) {
            return;
        }
        int x = this.searchBox.getX();
        int y = this.searchBox.getY() + this.searchBox.getHeight();
        int width = this.searchBox.getWidth();
        int height = this.completions.size() * COMPLETION_ROW_HEIGHT + 4;

        // 画在列表之上：super.render 已经把列表画完了，这里是最后一层
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COLOR_POPUP_BORDER);
        graphics.fill(x, y, x + width, y + height, COLOR_POPUP_BG);

        for (int i = 0; i < this.completions.size(); i++) {
            Candidate candidate = this.completions.get(i);
            int rowTop = y + 2 + i * COMPLETION_ROW_HEIGHT;
            boolean hovered = isOverCompletion(mouseX, mouseY, i);

            if (hovered) {
                graphics.fill(x + 1, rowTop, x + width - 1, rowTop + COMPLETION_ROW_HEIGHT, COLOR_POPUP_HOVER);
            }

            // 左：显示名 (条数)；右：命名空间 —— 一律是英文短名，不会撑爆
            Component label = Component.translatable("gui.whogoesthere.completion.entry",
                    candidate.displayName(), candidate.count());
            graphics.drawString(this.font, label, x + 3, rowTop + 2,
                    hovered ? COLOR_TEXT_HOVER : COLOR_TEXT);
            if (!candidate.namespace().equals(candidate.displayName())) {
                Component ns = Component.literal(candidate.namespace());
                graphics.drawString(this.font, ns, x + width - this.font.width(ns) - 3, rowTop + 2, COLOR_ACCENT);
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 自定义搜索框：把 Tab 抢过来做补全。
     *
     * <p>原版 {@link EditBox#keyPressed} 对 Tab(258) 返回 false，接着 {@code Screen} 会把它
     * 当成焦点切换；我们在这里返回 true 把它吃掉，所以制表符永远不会落进文本里。</p>
     */
    private class SearchBox extends EditBox {

        SearchBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_TAB && this.isActive() && this.isFocused()
                    && ScanScreen.this.acceptFirstCompletion()) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    private void onPick(EntityInfo entry) {
        Minecraft minecraft = this.minecraft;
        PacketDistributor.sendToServer(new HighlightRequestPayload(entry.entityId()));
        this.onClose();
        if (minecraft.player == null) {
            return;
        }

        // 坐标留 1 位小数，直接就是一条合法的 /tp 指令
        String command = String.format(Locale.ROOT, "/tp @s %.1f %.1f %.1f", entry.x(), entry.y(), entry.z());

        // 主体：只把指令填进聊天栏，玩家自己按回车 —— 手滑也不会把人传走
        MutableComponent body = Component.translatable("message.whogoesthere.located",
                        entry.name(),
                        coordsOf(entry),
                        entry.dimension().toString())
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.whogoesthere.tp.suggest.tooltip", command))));

        // 第二段：显眼的一下，点了就直接执行
        MutableComponent direct = Component.translatable("message.whogoesthere.tp.button")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.whogoesthere.tp.run.tooltip", command))));

        minecraft.player.displayClientMessage(body.append(" ").append(direct), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 浮层优先吃掉点击，别让它穿到下面的列表里
        if (button == 0) {
            for (int i = 0; i < this.completions.size(); i++) {
                if (isOverCompletion(mouseX, mouseY, i)) {
                    this.acceptCompletion(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 光标可能被方向键/点击挪过，每帧顺手校正一次（有缓存，几乎不花性能）
        this.refreshCompletions();

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, COLOR_TEXT);

        if (this.filtered.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.whogoesthere.empty"),
                    this.width / 2, this.listTop + this.listHeight / 2, COLOR_DIM);
        }

        this.renderCompletions(graphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** 坐标一律 1 位小数，与随包发来的距离精度一致。 */
    private static String coordsOf(EntityInfo entry) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", entry.x(), entry.y(), entry.z());
    }

    private static String shortDimension(ResourceLocation dimension) {
        return dimension.getPath();
    }

    /** 命名空间 → 模组显示名；取不到（或不是模组，比如 {@code minecraft}）就退回命名空间本身。 */
    private static String modDisplayName(String namespace) {
        return MOD_NAME_CACHE.computeIfAbsent(namespace, ns -> {
            try {
                return ModList.get().getModContainerById(ns)
                        .map(container -> container.getModInfo().getDisplayName())
                        .orElse(ns);
            } catch (Throwable t) {
                return ns;
            }
        });
    }

    /** 可滚动、可点击的结果列表。 */
    private class ResultList extends ObjectSelectionList<Row> {

        ResultList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setEntries(List<EntityInfo> entries) {
            this.clearEntries();
            for (EntityInfo entry : entries) {
                this.addEntry(new Row(entry));
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width - 6;
        }
    }

    /**
     * 一行：<br>
     * 第一行 —— [物品图标] 名字 ×N ……（右）所属模组<br>
     * 第二行 —— 距离 · 坐标 ……（右）维度
     */
    private class Row extends ObjectSelectionList.Entry<Row> {

        private final EntityInfo entry;

        Row(EntityInfo entry) {
            this.entry = entry;
        }

        /** 带数量的显示名——列表和朗读都用它，保证「僵尸 ×5」读出来一致。 */
        private Component label() {
            return this.entry.isStacked()
                    ? Component.translatable("gui.whogoesthere.row.count", this.entry.name(), this.entry.count())
                    : this.entry.name();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            int nameColor = hovered ? COLOR_TEXT_HOVER : COLOR_TEXT;
            int textLeft = left + 4;

            // 掉落物：先画 16×16 图标，文字往后让一格
            if (this.entry.isItem() && !this.entry.stack().isEmpty()) {
                graphics.renderItem(this.entry.stack(), textLeft, top + 3);
                textLeft += 20;
            }

            graphics.drawString(ScanScreen.this.font, this.label(), textLeft, top + 3, nameColor);

            Component detail = Component.translatable("gui.whogoesthere.row.detail",
                    String.format(Locale.ROOT, "%.1f", this.entry.distance()),
                    coordsOf(this.entry));
            graphics.drawString(ScanScreen.this.font, detail, textLeft, top + 13, COLOR_DIM);

            // 右上角：所属模组（这才是「按模组分类」看得见的那一半）
            Component mod = Component.literal(modDisplayName(this.entry.namespace()));
            graphics.drawString(ScanScreen.this.font, mod,
                    left + width - ScanScreen.this.font.width(mod) - 4, top + 3, COLOR_ACCENT);

            // 右下角：维度
            Component dimension = Component.literal(shortDimension(this.entry.dimension()));
            graphics.drawString(ScanScreen.this.font, dimension,
                    left + width - ScanScreen.this.font.width(dimension) - 4, top + 13, COLOR_DIM);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                ScanScreen.this.onPick(this.entry);
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("gui.whogoesthere.row.narration",
                    this.label(),
                    String.format(Locale.ROOT, "%.1f", this.entry.distance()),
                    coordsOf(this.entry));
        }
    }
}
