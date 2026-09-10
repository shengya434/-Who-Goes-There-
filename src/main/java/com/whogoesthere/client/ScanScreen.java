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
 * （{@code @apo} / {@code @apo 僵尸}），正在输入 {@code @} 词时下方会浮出候选模组
 * （完全不透明底 + 整块高亮，超出可见行数可滚动）。</p>
 *
 * <p>键盘：焦点可能落在上面的搜索框，也可能落到下面的结果列表上 —— 点滚动条
 * （{@code AbstractSelectionList#mouseClicked} 在滚动条命中时返回 true，于是
 * {@code Screen} 把焦点交给列表）或按 {@code Tab}（搜索框没候选时不消费 Tab，
 * 原版的 Tab 焦点循环会把焦点送进列表）都会让焦点跑过去。所以 {@code ↑/↓/Enter}
 * <b>两边都接</b>：{@link ScanScreen.ResultList#keyPressed} 和 {@link SearchBox#keyPressed}<br>
 * 浮层打开时 {@code ↑/↓} 在候选间循环移动高亮、{@code Enter} 补全高亮项（这时焦点一定在
 * 搜索框上，列表那份 keyPressed 也会让位给候选）；<br>
 * 浮层关闭时 {@code ↑/↓} 在结果列表里移动选中行（自动滚到可见）、{@code Enter} 确认选中行
 * （等价于鼠标点它）；{@code Tab} 在搜索框上是「补全第一个候选」的快捷方式。</p>
 *
 * <p>另外 {@link #keyPressed} 里还有一层 Enter 兜底：万一焦点两个控件都不认
 * （原版 {@code AbstractSelectionList} 根本没有 {@code keyPressed}，箭头是靠
 * {@code Screen} 的焦点导航挪选中行的，Enter 则谁都不管），回车也照样确认。</p>
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
    /** 键盘/鼠标选中的结果行：整块强调色底 + 亮字，明显区别于普通行。 */
    private static final int COLOR_ROW_SELECT_BORDER = 0xFF8CC8FF;
    private static final int COLOR_ROW_SELECT_BG = 0xFF1B3350;
    private static final int COLOR_TEXT_SELECTED = 0xFFFFFFFF;
    /** 置顶条目（盖了章的）名字用金色，一眼就能看出它是「永置顶」那一群。 */
    private static final int COLOR_PINNED = 0xFFFFD24A;

    /** 补全浮层最多列几个候选。 */
    private static final int MAX_COMPLETIONS = 12;
    /** 补全浮层每行高度（比结果行紧凑）。 */
    private static final int COMPLETION_ROW_HEIGHT = 12;
    /** 浮层一次最多显示几行，超出就滚动（高亮行会跟着滚进可见区）。 */
    private static final int COMPLETION_VISIBLE_ROWS = 6;
    /**
     * 浮层配色：一律 alpha = 0xFF 的纯色底 —— 不用 tooltip 那种半透明/渐变模糊底，
     * 实机看着才不糊。统一深底 + 亮字；选中行画整块高亮，而不是只换文字颜色。
     */
    private static final int COLOR_POPUP_BG = 0xFF0E1220;
    private static final int COLOR_POPUP_BORDER = 0xFF8CC8FF;
    private static final int COLOR_POPUP_SELECT = 0xFF2D5C93;
    private static final int COLOR_POPUP_HOVER = 0xFF22344F;
    private static final int COLOR_POPUP_TEXT = 0xFFE8E8E8;
    private static final int COLOR_POPUP_TEXT_ON = 0xFFFFFFFF;
    private static final int COLOR_POPUP_NS = 0xFF9FD4FF;
    private static final int COLOR_POPUP_TRACK = 0xFF2A2A2A;
    /**
     * 浮窗抬升到的 pose z。
     *
     * <p>MC 1.21.1 的 GUI 绘制是<b>带深度测试</b>的，而且 vanilla 自己靠 pose 的 z 分层：
     * {@code GuiGraphics.renderItem} 把物品图标推到 {@code z=150}
     * （{@code PoseStack.translate(x+8, y+8, 150)}，见字节码 {@code sipush 150}），
     * {@code renderTooltipInternal} 又把底和字都放到 {@code z=400}
     * （{@code translate(0,0,400)} + {@code TooltipRenderUtil.renderTooltipBackground(..., 400, ...)}）。</p>
     *
     * <p>而 {@code GuiGraphics.fill}/{@code drawString} 默认都在 {@code z=0}，也就是这一套里
     * <b>最远</b>的一层。深度测试是 {@code LEQUAL}：后来者要“更近或相等”才能落笔，所以
     * 只要被覆盖区域里已经有 {@code z>0} 的像素（结果列表里掉落物行的物品图标就是），
     * 浮窗底/字就会被丢掉，露出下面的列表 —— 这就是“浮窗透字/重影”的真因。</p>
     *
     * <p>修法与 vanilla 一致：整块浮窗抬到 400（与 tooltip 同层、且高于图标的 150）。
     * 底与字必须<b>同一 z</b>、且底先画（同 z 时 {@code LEQUAL} 相等即通过）。</p>
     */
    private static final float POPUP_Z = 400.0F;

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
    /** 结果列表当前边界（浮窗打开时会被 {@link #layoutResultList()} 下移/压扁）。 */
    private int listTop;
    private int listHeight;
    /** 浮窗关闭时结果列表的基准边界；浮窗打开时在此基础上让位，关闭后还原。 */
    private int baseListTop;
    private int baseListHeight;

    /** 光标处正在输入的 @ 词（含前导 @）；null = 当前没有补全上下文。 */
    private String completionWord;
    private int completionWordStart;
    private int completionWordEnd;
    /** 排好序的候选（最多 {@value #MAX_COMPLETIONS} 个）。 */
    private final List<Candidate> completions = new ArrayList<>();
    /** 浮层里当前高亮的候选下标：浮层打开时 ↑/↓ 改它，Enter 补全它。 */
    private int completionHighlight;
    /** 浮层滚动偏移：可见窗口的第一行对应第几个候选。 */
    private int completionScroll;
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
        // 记下基准边界：浮窗关闭时列表就用它；浮窗打开时 layoutResultList() 会在此基础上
        // 把列表整体压到浮窗下沿之下，关闭后还原到这里。
        this.baseListTop = 56;
        this.baseListHeight = Math.max(ROW_HEIGHT * 2, this.height - this.baseListTop - 32);
        this.listTop = this.baseListTop;
        this.listHeight = this.baseListHeight;

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
            // 候选集跟着文本变了 —— 高亮和滚动都回到开头
            this.completionHighlight = 0;
            this.completionScroll = 0;
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
        this.completionHighlight = 0;
        this.completionScroll = 0;
        this.completionWord = null;
        this.completionKey = null;
    }

    /** ↑/↓：在候选之间循环移动高亮（到顶再按 ↑ 回到最后一个），并让高亮行滚进可见窗口。 */
    private void moveCompletion(int delta) {
        int size = this.completions.size();
        if (size == 0) {
            return;
        }
        int next = (this.completionHighlight + delta) % size;
        if (next < 0) {
            next += size;
        }
        this.completionHighlight = next;

        int visibleRows = Math.min(COMPLETION_VISIBLE_ROWS, size);
        if (next < this.completionScroll) {
            this.completionScroll = next;
        } else if (next >= this.completionScroll + visibleRows) {
            this.completionScroll = next - visibleRows + 1;
        }
    }

    /** Enter：补全当前高亮的候选（等价于鼠标点它）。 */
    private void acceptHighlightedCompletion() {
        this.acceptCompletion(this.completionHighlight);
    }

    /** Tab：补全到第一个候选；没有候选就当作没按过，让按键继续往下走。 */
    private boolean acceptFirstCompletion() {
        if (this.completions.isEmpty()) {
            return false;
        }
        this.acceptCompletion(0);
        return true;
    }

    // ------------------------------------------------------------------
    // 浮窗几何 + 列表布局兜底
    // ------------------------------------------------------------------

    /** 浮窗顶边 y：贴在搜索框正下方。 */
    private int popupTopY() {
        return this.searchBox.getY() + this.searchBox.getHeight();
    }

    /** 浮窗这次实际显示几行（不超过候选数）。 */
    private int visibleCompletionRows() {
        return Math.min(COMPLETION_VISIBLE_ROWS, this.completions.size());
    }

    /** 浮窗高度：可见行 + 上下各 2px 内边距。 */
    private int popupHeight() {
        return visibleCompletionRows() * COMPLETION_ROW_HEIGHT + 4;
    }

    /** 浮窗下沿 y。 */
    private int popupBottomY() {
        return this.popupTopY() + this.popupHeight();
    }

    /**
     * 结果列表的确定性布局兜底。
     *
     * <p>浮窗打开时把 {@code resultList} 整体挪到浮窗下沿之下、并相应压扁，浮窗关闭时还原。</p>
     *
     * <p>这是对上面 {@link #POPUP_Z} 那套“抬层”机制的第二重保险：即使某个环境下深度/层级
     * 机制再次失效（或别的方块把浮窗位置改了），浮窗和列表在几何上就<b>不重叠</b>，
     * 不可能再出现两种文字叠在一起。</p>
     *
     * <p>只动 {@code setY}/{@code setHeight}：{@code updateSizeAndPosition(w,h,y)} 会把 x 设成 0，
     * 用了列表就贴到屏幕左边，所以这里绝不碰它。</p>
     */
    private void layoutResultList() {
        if (this.resultList == null) {
            return;
        }
        int bottom = this.height - 32;
        int top = this.baseListTop;
        int height = this.baseListHeight;
        if (!this.completions.isEmpty()) {
            int shifted = Math.max(this.baseListTop, this.popupBottomY() + 4);
            top = shifted;
            height = Math.max(ROW_HEIGHT, bottom - shifted);
        }
        if (this.resultList.getY() != top) {
            this.resultList.setY(top);
        }
        if (this.resultList.getHeight() != height) {
            this.resultList.setHeight(height);
            this.resultList.clampScrollAmount();
        }
        this.listTop = top;
        this.listHeight = height;
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
        int y = this.popupTopY();
        int width = this.searchBox.getWidth();
        int visibleRows = this.visibleCompletionRows();
        int height = this.popupHeight();
        boolean scrollbar = this.completions.size() > visibleRows;
        // 有滚动条时给右侧让出 4px，别让命名空间文字压上去
        int rightMargin = scrollbar ? 7 : 3;

        // 【真因】MC 1.21.1 的 GUI 带深度测试（RenderType.GUI = LEQUAL_DEPTH_TEST + 写深度），
        // vanilla 靠 pose z 分层：物品图标 renderItem 在 z=150，tooltip 在 z=400，而 fill/drawString
        // 默认在 z=0 —— 最远的一层，覆盖不到已经写入的更近像素（掉落物行的物品图标）。
        // 所以这里把整块浮窗抬到 z=400：底、边、行、滚动条全在同一 z，且底先画。
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, POPUP_Z);

        // 画在列表之上：super.render 已经把列表画完了，这里是最后一层。
        // 底和边都是 alpha=0xFF 的纯色 —— 完全不透明，不再糊。
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COLOR_POPUP_BORDER);
        graphics.fill(x, y, x + width, y + height, COLOR_POPUP_BG);

        for (int i = 0; i < visibleRows; i++) {
            int index = this.completionScroll + i;
            if (index >= this.completions.size()) {
                break;
            }
            Candidate candidate = this.completions.get(index);
            int rowTop = y + 2 + i * COMPLETION_ROW_HEIGHT;
            boolean selected = index == this.completionHighlight;
            boolean hovered = isOverCompletion(mouseX, mouseY, i);
            int rowRight = x + width - 1 - (scrollbar ? 4 : 0);

            // 高亮是整块底色（不是只改文字色）；选中 > 悬停
            if (selected) {
                graphics.fill(x + 1, rowTop, rowRight, rowTop + COMPLETION_ROW_HEIGHT, COLOR_POPUP_SELECT);
            } else if (hovered) {
                graphics.fill(x + 1, rowTop, rowRight, rowTop + COMPLETION_ROW_HEIGHT, COLOR_POPUP_HOVER);
            }

            // 左：显示名 (条数)；右：命名空间 —— 一律是英文短名，不会撑爆
            Component label = Component.translatable("gui.whogoesthere.completion.entry",
                    candidate.displayName(), candidate.count());
            graphics.drawString(this.font, label, x + 3, rowTop + 2,
                    (selected || hovered) ? COLOR_POPUP_TEXT_ON : COLOR_POPUP_TEXT);
            if (!candidate.namespace().equals(candidate.displayName())) {
                Component ns = Component.literal(candidate.namespace());
                graphics.drawString(this.font, ns, x + width - rightMargin - this.font.width(ns), rowTop + 2,
                        COLOR_POPUP_NS);
            }
        }

        // 候选多于可见行数时，右侧画一根细滚动条 —— 看得到自己滚到哪了
        if (scrollbar) {
            int trackTop = y + 2;
            int trackBottom = y + height - 2;
            int trackHeight = trackBottom - trackTop;
            int barX = x + width - 3;
            graphics.fill(barX, trackTop, barX + 2, trackBottom, COLOR_POPUP_TRACK);
            int thumbHeight = Math.max(6, trackHeight * visibleRows / this.completions.size());
            int maxScroll = this.completions.size() - visibleRows;
            int thumbTop = trackTop + (trackHeight - thumbHeight) * this.completionScroll / Math.max(1, maxScroll);
            graphics.fill(barX, thumbTop, barX + 2, thumbTop + thumbHeight, COLOR_POPUP_BORDER);
        }

        // 与上面的 pushPose 配对，别把 400 的 z 泄漏给后面的绘制
        graphics.pose().popPose();
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
            // 搜索框始终持有焦点，所以 ↑/↓/Enter/Tab 都得在这里自己消化并 return true，
            // 否则会漏给 Screen 去做焦点切换 / 列表滚动。
            ScanScreen.this.refreshCompletions();

            if (!ScanScreen.this.completions.isEmpty()) {
                // 浮层打开：↑/↓ 在候选项之间走（循环），Enter 补全高亮的那一项
                switch (keyCode) {
                    case GLFW.GLFW_KEY_UP -> {
                        ScanScreen.this.moveCompletion(-1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_DOWN -> {
                        ScanScreen.this.moveCompletion(1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        ScanScreen.this.acceptHighlightedCompletion();
                        return true;
                    }
                    default -> {
                    }
                }
            } else {
                // 浮层关闭：↑/↓ 在结果列表里走，Enter 确认选中行
                // （焦点在列表上时是同名逻辑的另一份，见 ResultList#keyPressed ——
                //  点滚动条或按 Tab 都会把焦点弄到那边去，那边也必须能确认）
                switch (keyCode) {
                    case GLFW.GLFW_KEY_UP -> {
                        if (ScanScreen.this.resultList.moveSelection(-1)) {
                            return true;
                        }
                    }
                    case GLFW.GLFW_KEY_DOWN -> {
                        if (ScanScreen.this.resultList.moveSelection(1)) {
                            return true;
                        }
                    }
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        if (ScanScreen.this.resultList.confirmSelection()) {
                            return true;
                        }
                    }
                    default -> {
                    }
                }
            }

            // Tab：补全到第一个候选（保留的快捷方式）
            if (keyCode == GLFW.GLFW_KEY_TAB && this.isActive() && this.isFocused()
                    && ScanScreen.this.acceptFirstCompletion()) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    private void onPick(EntityInfo entry) {
        Minecraft minecraft = this.minecraft;
        if (entry.isPinned() && entry.uuid() != null) {
            // 置顶条目可能跑到别的维度去了，得靠 UUID + 维度定位
            PacketDistributor.sendToServer(HighlightRequestPayload.ofUuid(entry.uuid(), entry.dimension()));
        } else {
            PacketDistributor.sendToServer(HighlightRequestPayload.ofEntity(entry.entityId()));
        }
        this.onClose();
        if (minecraft.player == null) {
            return;
        }

        // 同维度直接 /tp；异维度（置顶条目可能跨维度）得先用 execute in 切过去。
        // 坐标留 1 位小数，直接就是一条合法指令。
        ResourceLocation myDimension = minecraft.level == null ? null : minecraft.level.dimension().location();
        boolean sameDimension = myDimension != null && entry.dimension().equals(myDimension);
        String command = sameDimension
                ? String.format(Locale.ROOT, "/tp @s %.1f %.1f %.1f", entry.x(), entry.y(), entry.z())
                : String.format(Locale.ROOT, "/execute in %s run tp @s %.1f %.1f %.1f",
                        entry.dimension(), entry.x(), entry.y(), entry.z());

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

    /**
     * 正常派发之外，再给 Enter 加一层兜底。
     *
     * <p>按键先照原样派发给持有焦点的控件（{@code super.keyPressed}）：
     * 搜索框、结果列表都能自己吃掉 {@code ↑/↓/Enter}，正常情况到不了这里。
     * 万一两边都没接（例如焦点被 {@code clearFocus} 清掉），回车仍然确认列表选中行 ——
     * 「高亮看得见、回车没人管」这种状态在新代码里不该再出现。</p>
     *
     * <p>浮层打开时不插手：那条 {@code Enter} 是「补全候选」，一定先被搜索框返回 true 吃掉。</p>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && this.completions.isEmpty()
                && this.resultList != null
                && this.resultList.confirmSelection()) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 浮层优先吃掉点击，别让它穿到下面的列表里（点击只认可见窗口内的行）
        if (button == 0) {
            int visibleRows = Math.min(COMPLETION_VISIBLE_ROWS, this.completions.size());
            for (int i = 0; i < visibleRows; i++) {
                if (isOverCompletion(mouseX, mouseY, i)) {
                    this.acceptCompletion(this.completionScroll + i);
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
        // 候选集可能刚变过 —— 先把列表让位/还原算好，再画
        this.layoutResultList();

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
            // 重建前先记住「现在选的是哪一条」：能按身份找回同一行就选它，找不到就把下标
            // 夹回合法范围（这样改搜索词/重扫之后回车仍然按得响）。
            int oldIndex = this.selectedIndex >= 0 ? this.selectedIndex : this.children().indexOf(this.getSelected());
            Row oldSelected = this.rowAt(oldIndex);

            this.clearEntries();
            // 原版 clearEntries() 直接给 selected 字段赋值 null，绕过 setSelected，
            // 所以这里必须自己把下标也清掉，否则会留下一个指向已废行的幽灵下标。
            this.selectedIndex = -1;
            for (EntityInfo entry : entries) {
                this.addEntry(new Row(entry));
            }
            if (this.getItemCount() == 0) {
                return;
            }
            if (oldSelected != null) {
                int restored = -1;
                for (int i = 0; i < this.getItemCount(); i++) {
                    if (sameEntry(oldSelected.entry, this.getEntry(i).entry)) {
                        restored = i;
                        break;
                    }
                }
                if (restored < 0) {
                    restored = Math.max(0, Math.min(oldIndex, this.getItemCount() - 1));
                }
                this.setSelected(this.getEntry(restored));
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

        /**
         * 原版选中的唯一入口：不管谁改了选中（我们自己、鼠标、原版焦点导航），
         * 都把下标同步过来 —— 确认与高亮之后一律只认这份下标。
         */
        @Override
        public void setSelected(Row row) {
            this.selectedIndex = row == null ? -1 : this.children().indexOf(row);
            super.setSelected(row);
        }

        /** 下标 → 行；越界/空表返回 null。 */
        private Row rowAt(int index) {
            return index >= 0 && index < this.getItemCount() ? this.getEntry(index) : null;
        }

        /** 键盘移动选中行，并自动滚到可见；列表为空返回 false（让按键继续往下走）。 */
        boolean moveSelection(int delta) {
            int count = this.getItemCount();
            if (count == 0) {
                return false;
            }
            int index = this.selectedIndex >= 0
                    ? this.selectedIndex
                    : this.children().indexOf(this.getSelected());
            int next = index < 0
                    ? (delta > 0 ? 0 : count - 1)
                    : Math.max(0, Math.min(count - 1, index + delta));
            Row row = this.getEntry(next);
            this.setSelected(row);
            this.ensureVisible(row);
            return true;
        }

        /** Enter：等价于鼠标点中选中的那一行（发光 + 坐标消息 + 关屏）；没选中返回 false。 */
        boolean confirmSelection() {
            // 先认我们自己的下标；万一它对不上（外部把列表重建过），再退到原版的选中。
            // 两边任意一个活着，回车就打得响 —— 这就是「高亮在、回车没反应」的根治点。
            Row row = this.rowAt(this.selectedIndex);
            if (row == null) {
                row = this.getSelected();
                if (row != null) {
                    this.setSelected(row);
                }
            }
            if (row == null) {
                return false;
            }
            ScanScreen.this.onPick(row.entry);
            return true;
        }

        /**
         * 焦点在列表上时的键盘处理。
         *
         * <p>原版 {@code AbstractSelectionList} 没有 {@code keyPressed}：焦点在列表上时
         * {@code ↑/↓} 是 {@code Screen} 的箭头焦点导航在挪选中行（所以高亮会动），而
         * {@code Enter} 谁都不管 —— 这就是玩家报的「上下键选中了、回车确认不了」。</p>
         */
        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // 浮层打开时列表本来拿不到焦点；真拿到了也只准动候选，绝不动列表选中行
            if (!ScanScreen.this.completions.isEmpty()) {
                switch (keyCode) {
                    case GLFW.GLFW_KEY_UP -> {
                        ScanScreen.this.moveCompletion(-1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_DOWN -> {
                        ScanScreen.this.moveCompletion(1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        ScanScreen.this.acceptHighlightedCompletion();
                        return true;
                    }
                    default -> {
                    }
                }
            }
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> {
                    if (this.moveSelection(-1)) {
                        return true;
                    }
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    if (this.moveSelection(1)) {
                        return true;
                    }
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (this.confirmSelection()) {
                        return true;
                    }
                }
                default -> {
                }
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void renderSelection(GuiGraphics graphics, int top, int width, int height,
                                       int outerColor, int innerColor) {
            // 用强调色块标出选中的那行 —— 比原版灰框明显得多，键盘操作才看得见。
            // 位置一律按我们自己的下标算：原版传进来的 top 是照它自己的 selected 算的，不用。
            int index = this.selectedIndex;
            if (index < 0 || index >= this.getItemCount()) {
                return;
            }
            int rowTop = this.getRowTop(index);
            int left = this.getX() + (this.width - width) / 2;
            int right = this.getX() + (this.width + width) / 2;
            graphics.fill(left, rowTop - 2, right, rowTop + height + 2, COLOR_ROW_SELECT_BORDER);
            graphics.fill(left + 1, rowTop - 1, right - 1, rowTop + height + 1, COLOR_ROW_SELECT_BG);
        }

        /**
         * 两条记录算不算「同一条」：置顶的认 UUID（它跨维度也会被重扫出来）；
         * 其余认「注册 id + 显示名」。只用来在列表重建后把选中行挪到对应位置，
         * 对不上就退化为「保留下标」，所以不要求绝对唯一。
         */
        private static boolean sameEntry(EntityInfo a, EntityInfo b) {
            if (a.uuid() != null || b.uuid() != null) {
                return a.uuid() != null && a.uuid().equals(b.uuid());
            }
            return a.typeId().equals(b.typeId()) && a.name().equals(b.name());
        }

        /** 当前选中行的下标（-1 = 没选中）；高亮、确认都只认它。 */
        private int selectedIndex = -1;
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

        /** 带数量的显示名——列表和朗读都用它，保证「僵尸 ×5」读出来一致；置顶的加一颗星。 */
        private Component label() {
            Component base = this.entry.isStacked()
                    ? Component.translatable("gui.whogoesthere.row.count", this.entry.name(), this.entry.count())
                    : this.entry.name();
            return this.entry.isPinned() ? Component.translatable("gui.whogoesthere.row.pinned", base) : base;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            // 键盘/鼠标选中的那一行也换个亮色文字，跟底色块配合；置顶条目平时走金色。
            // 认列表自己的下标，跟 renderSelection 画的那块底板同一个来源，不会两处打架。
            boolean selected = index == ScanScreen.this.resultList.selectedIndex;
            int nameColor = selected ? COLOR_TEXT_SELECTED
                    : (hovered ? COLOR_TEXT_HOVER : (this.entry.isPinned() ? COLOR_PINNED : COLOR_TEXT));
            int textLeft = left + 4;

            // 掉落物：先画 16×16 图标，文字往后让一格
            if (this.entry.isItem() && !this.entry.stack().isEmpty()) {
                graphics.renderItem(this.entry.stack(), textLeft, top + 3);
                textLeft += 20;
            }

            graphics.drawString(ScanScreen.this.font, this.label(), textLeft, top + 3, nameColor);

            Component detail = this.entry.isCrossDimension()
                    ? Component.translatable("gui.whogoesthere.row.detail.crossdim", coordsOf(this.entry))
                    : Component.translatable("gui.whogoesthere.row.detail",
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
