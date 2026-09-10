package com.whogoesthere.client;

import com.whogoesthere.client.compat.PinyinSearchCompat;
import com.whogoesthere.network.payload.HighlightRequestPayload;
import com.whogoesthere.network.payload.ScanResultPayload;
import com.whogoesthere.network.payload.ScanResultPayload.EntityInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

/**
 * 仅客户端：「谁在那！」结果面板。
 *
 * <p>上面一个搜索框（按名字过滤）+ 一个「模组筛选」循环按钮，下面一个可滚动列表。
 * 点某一行就请服务端给它打发光标记，关掉界面并在聊天栏里报出它的坐标——
 * 那条消息本身还能点：点主体把 {@code /tp} 填进聊天栏，点 {@code [直接传送]} 直接执行。</p>
 */
public class ScanScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_MARGIN = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HOVER = 0xFFFFE08A;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF8CC8FF;

    /** 模组显示名缓存：setEntries 之后每帧都会画行，别每帧去翻 ModList。 */
    private static final Map<String, String> MOD_NAME_CACHE = new HashMap<>();

    private final List<EntityInfo> allEntries;
    /** 本次结果里实际出现过的命名空间，去重 + 排序。 */
    private final List<String> namespaces;
    private List<EntityInfo> filtered;
    private String filterText = "";
    /** null = 全部模组。 */
    private String selectedNamespace;

    private EditBox searchBox;
    private ResultList resultList;
    private int listTop;
    private int listHeight;

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

        // 搜索框左对齐列表，模组筛选按钮占右侧 —— 模组显示名可能很长，给它留够宽度
        int gap = 6;
        int buttonWidth = Math.min(170, Math.max(90, this.width / 3));
        int searchWidth = Math.max(80, this.width - LIST_MARGIN * 2 - gap - buttonWidth);

        this.searchBox = new EditBox(this.font, LIST_MARGIN, 28, searchWidth, 20,
                Component.translatable("gui.whogoesthere.search"));
        this.searchBox.setHint(Component.translatable("gui.whogoesthere.search.hint"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::applyFilter);
        this.searchBox.setValue(this.filterText);
        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        Button filter = Button.builder(this.modFilterLabel(), button -> {
            this.cycleNamespaceFilter();
            button.setMessage(this.modFilterLabel());
        }).bounds(LIST_MARGIN + searchWidth + gap, 28, buttonWidth, 20).build();
        filter.active = !this.namespaces.isEmpty();
        this.addRenderableWidget(filter);

        this.applyFilter(this.filterText);
    }

    /** 循环：全部 → 第 1 个模组 → … → 最后 1 个模组 → 全部。 */
    private void cycleNamespaceFilter() {
        if (this.namespaces.isEmpty()) {
            this.selectedNamespace = null;
        } else if (this.selectedNamespace == null) {
            this.selectedNamespace = this.namespaces.get(0);
        } else {
            int index = this.namespaces.indexOf(this.selectedNamespace);
            this.selectedNamespace = index < 0 || index + 1 >= this.namespaces.size()
                    ? null
                    : this.namespaces.get(index + 1);
        }
        this.applyFilter(this.filterText);
    }

    private Component modFilterLabel() {
        if (this.selectedNamespace == null) {
            return Component.translatable("gui.whogoesthere.filter.all");
        }
        return Component.translatable("gui.whogoesthere.filter.mod", modDisplayName(this.selectedNamespace));
    }

    private void applyFilter(String rawQuery) {
        this.filterText = rawQuery == null ? "" : rawQuery;
        String query = this.filterText.trim().toLowerCase(Locale.ROOT);

        List<EntityInfo> matches = new ArrayList<>();
        for (EntityInfo entry : this.allEntries) {
            if (this.selectedNamespace != null && !this.selectedNamespace.equals(entry.namespace())) {
                continue;
            }
            if (query.isEmpty() || matchesQuery(entry, query)) {
                matches.add(entry);
            }
        }
        this.filtered = matches;
        if (this.resultList != null) {
            this.resultList.setEntries(matches);
        }
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, COLOR_TEXT);

        if (this.filtered.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.whogoesthere.empty"),
                    this.width / 2, this.listTop + this.listHeight / 2, COLOR_DIM);
        }
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
