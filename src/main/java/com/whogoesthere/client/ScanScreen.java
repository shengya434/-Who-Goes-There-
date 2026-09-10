package com.whogoesthere.client;

import com.whogoesthere.client.compat.PinyinSearchCompat;
import com.whogoesthere.network.payload.HighlightRequestPayload;
import com.whogoesthere.network.payload.ScanResultPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 仅客户端：「谁在那！」结果面板。
 *
 * <p>上面一个搜索框（按名字过滤），下面一个可滚动列表。点某一行就请服务端给它打发光标记，
 * 关掉界面并在聊天栏里报出它的坐标。</p>
 */
public class ScanScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_MARGIN = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HOVER = 0xFFFFE08A;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF8CC8FF;

    private final List<ScanResultPayload.EntityInfo> allEntries;
    private List<ScanResultPayload.EntityInfo> filtered;
    private String filterText = "";

    private EditBox searchBox;
    private ResultList resultList;
    private int listTop;
    private int listHeight;

    public ScanScreen(List<ScanResultPayload.EntityInfo> entries) {
        super(Component.translatable("gui.whogoesthere.title"));
        this.allEntries = List.copyOf(entries);
        this.filtered = List.copyOf(entries);
    }

    @Override
    protected void init() {
        this.listTop = 56;
        this.listHeight = Math.max(ROW_HEIGHT * 2, this.height - this.listTop - 32);

        this.resultList = new ResultList(this.minecraft, this.width - LIST_MARGIN * 2, this.listHeight, this.listTop, ROW_HEIGHT);
        this.resultList.setX(LIST_MARGIN);
        this.addRenderableWidget(this.resultList);

        this.searchBox = new EditBox(this.font, this.width / 2 - 100, 28, 200, 20,
                Component.translatable("gui.whogoesthere.search"));
        this.searchBox.setHint(Component.translatable("gui.whogoesthere.search.hint"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::applyFilter);
        this.searchBox.setValue(this.filterText);
        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.applyFilter(this.filterText);
    }

    private void applyFilter(String rawQuery) {
        this.filterText = rawQuery == null ? "" : rawQuery;
        String query = this.filterText.trim().toLowerCase(Locale.ROOT);

        List<ScanResultPayload.EntityInfo> matches = new ArrayList<>();
        for (ScanResultPayload.EntityInfo entry : this.allEntries) {
            // 名字走 PinyinSearchCompat（装了 JECh 就是拼音/首字母匹配），
            // 再补一条英文注册名（type id）匹配，方便输入 "zombie" 找「僵尸」。
            if (query.isEmpty()
                    || PinyinSearchCompat.matches(entry.name().getString(), query)
                    || entry.typeId().toString().toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(entry);
            }
        }
        this.filtered = matches;
        if (this.resultList != null) {
            this.resultList.setEntries(matches);
        }
    }

    private void onPick(ScanResultPayload.EntityInfo entry) {
        Minecraft minecraft = this.minecraft;
        PacketDistributor.sendToServer(new HighlightRequestPayload(entry.entityId()));
        this.onClose();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "message.whogoesthere.located",
                    entry.name(),
                    coordsOf(entry),
                    entry.dimension().toString()), false);
        }
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

    private static String coordsOf(ScanResultPayload.EntityInfo entry) {
        return String.format(Locale.ROOT, "%d %d %d",
                (int) Math.floor(entry.x()), (int) Math.floor(entry.y()), (int) Math.floor(entry.z()));
    }

    private static String shortDimension(ResourceLocation dimension) {
        return dimension.getPath();
    }

    /** 可滚动、可点击的结果列表。 */
    private class ResultList extends ObjectSelectionList<Row> {

        ResultList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setEntries(List<ScanResultPayload.EntityInfo> entries) {
            this.clearEntries();
            for (ScanResultPayload.EntityInfo entry : entries) {
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

    /** 一行：名字 · 距离 · 坐标（点一下 = 定位）。 */
    private class Row extends ObjectSelectionList.Entry<Row> {

        private final ScanResultPayload.EntityInfo entry;

        Row(ScanResultPayload.EntityInfo entry) {
            this.entry = entry;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            int nameColor = hovered ? COLOR_TEXT_HOVER : COLOR_TEXT;
            graphics.drawString(ScanScreen.this.font, this.entry.name(), left + 4, top + 3, nameColor);

            Component detail = Component.translatable("gui.whogoesthere.row.detail",
                    String.format(Locale.ROOT, "%.1f", this.entry.distance()),
                    coordsOf(this.entry));
            graphics.drawString(ScanScreen.this.font, detail, left + 4, top + 13, COLOR_DIM);

            Component dimension = Component.literal(shortDimension(this.entry.dimension()));
            graphics.drawString(ScanScreen.this.font, dimension,
                    left + width - ScanScreen.this.font.width(dimension) - 4, top + 3, COLOR_ACCENT);
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
                    this.entry.name(),
                    String.format(Locale.ROOT, "%.1f", this.entry.distance()),
                    coordsOf(this.entry));
        }
    }
}
