package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.CreateProjectC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalListS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalSearchC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.OpenTerminalS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GoalPickerScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_ROWS = 7;
    /** Five client ticks coalesce ordinary typing and stay below the server request gate. */
    private static final int SEARCH_DEBOUNCE_TICKS = 5;
    /** Where the list starts, below the search and quantity fields. */
    private static final int LIST_TOP = 55;
    /** Clear space between the last row and the button strip. */
    private static final int LIST_BOTTOM_GAP = 8;
    private final Screen parent;
    private final OpenTerminalS2C snapshot;
    private final List<GoalWire> filtered = new ArrayList<>();
    private EditBox search;
    private String pendingQuery = "";
    private int searchDelayTicks = -1;
    private boolean searchInFlight;
    private EditBox quantity;
    private Button select;
    private Button mode;
    private int scroll;
    private int selected = -1;
    private PlayerExecutionMode executionMode = PlayerExecutionMode.SMART_RECOMMENDED;
    private String failureCode = "";

    public GoalPickerScreen(Screen parent, OpenTerminalS2C snapshot) {
        super(Component.translatable("screen.steve_create_agent.goal_picker"));
        this.parent = parent;
        this.snapshot = snapshot;
        filtered.addAll(snapshot.goals());
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        String existingSearch = search == null ? "" : search.getValue();
        String existingQuantity = quantity == null ? "1" : quantity.getValue();
        search = new EditBox(font, left, 28, 205, 20,
                Component.translatable("screen.steve_create_agent.search"));
        search.setHint(Component.translatable("screen.steve_create_agent.search"));
        search.setMaxLength(PlayerWorkflowPackets.MAX_QUERY);
        search.setValue(existingSearch);
        search.setResponder(this::filter);
        addRenderableWidget(search);
        quantity = new EditBox(font, left + 215, 28, 40, 20,
                Component.translatable("screen.steve_create_agent.quantity"));
        quantity.setFilter(value -> value.isEmpty() || value.matches("[0-9]{1,2}"));
        quantity.setValue(existingQuantity);
        quantity.setResponder(ignored -> updateSelectState());
        addRenderableWidget(quantity);
        mode = addRenderableWidget(Button.builder(modeLabel(), ignored -> cycleMode())
                .bounds(left, height - 50, 140, 20).build());
        select = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.select"), ignored -> submit())
                .bounds(left + 145, height - 50, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.steve_create_agent.back"),
                ignored -> minecraft.setScreen(parent)).bounds(left + 240, height - 50, 60, 20).build());
        // init runs again when the window is resized, and a smaller window fits fewer
        // rows — a scroll position from the larger one would leave the list showing
        // blank space below the last entry.
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - visibleRows())));
        updateSelectState();
    }

    /**
     * A name a player can read.
     *
     * <p>The goal translation key only exists for the eleven reviewed entries; the two
     * hundred derived ones have none, so every row rendered its own key —
     * "goal.steve_create_agent.minecraft.black_concrete" where the player expected
     * "Black Concrete". Nobody noticed while the picker could only ever show the eleven.
     *
     * <p>Falls back to the item's own name, which Minecraft has already translated into
     * whatever language the player is using.</p>
     */
    private static Component label(GoalWire goal) {
        if (net.minecraft.client.resources.language.I18n.exists(goal.translationKey())) {
            return Component.translatable(goal.translationKey());
        }
        ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(goal.target()))
                .getDefaultInstance();
        return stack.isEmpty() ? Component.literal(goal.target()) : stack.getHoverName();
    }

    /**
     * How many rows fit above the buttons, which is not always seven.
     *
     * <p>The list drew a fixed seven rows from a fixed y, and the buttons sit relative to
     * the window height — so in a window shorter than about 260 pixels the last row lay
     * underneath them. Clicking "select target" selected whatever was in that row
     * instead, and the player could not press the button at all.
     *
     * <p>Every place that reasons about rows reads this: the renderer, the click test and
     * the scroll bound. They each had their own copy of the seven, which is how the
     * renderer and the click test could disagree about where a row is.</p>
     */
    private int visibleRows() {
        int available = buttonStripTop() - LIST_BOTTOM_GAP - LIST_TOP;
        return Math.max(1, Math.min(MAX_VISIBLE_ROWS, available / ROW_HEIGHT));
    }

    /** The top of the button strip, which is what the list must not reach. */
    private int buttonStripTop() {
        return height - 50;
    }

    /**
     * Asks the server what matches, instead of filtering the eleven rows already here.
     *
     * <p>The snapshot this screen opens with is what an empty query returns — the
     * reviewed catalog. Filtering it locally meant the two hundred derived targets could
     * never appear no matter what was typed: "co" offered a cogwheel and a cooked steak
     * while the registry held cobblestone, copper and concrete.
     *
     * <p>An empty query still resolves locally. It is the same list, it is already here,
     * and a round trip to redisplay what the screen opened with would only make clearing
     * the box feel slow.</p>
     */
    private void filter(String value) {
        String query = value.toLowerCase(Locale.ROOT).trim();
        if (query.isEmpty()) {
            pendingQuery = "";
            searchDelayTicks = -1;
            searchInFlight = false;
            filtered.clear();
            filtered.addAll(snapshot.goals());
            selected = -1;
            scroll = 0;
            updateSelectState();
            return;
        }
        pendingQuery = query;
        searchDelayTicks = SEARCH_DEBOUNCE_TICKS;
        searchInFlight = true;
        filtered.clear();
        selected = -1;
        scroll = 0;
        failureCode = "";
        updateSelectState();
    }

    @Override
    public void tick() {
        super.tick();
        if (search != null) search.tick();
        if (searchDelayTicks > 0 && --searchDelayTicks == 0) {
            PlayerWorkflowNetwork.searchGoals(
                    new GoalSearchC2S(snapshot.terminalNonce(), pendingQuery));
        }
    }

    private void cycleMode() {
        executionMode = switch (executionMode) {
            case SMART_RECOMMENDED -> PlayerExecutionMode.DIRECT;
            case DIRECT -> PlayerExecutionMode.BOTS;
            case BOTS -> PlayerExecutionMode.HYBRID;
            case HYBRID -> PlayerExecutionMode.SMART_RECOMMENDED;
        };
        mode.setMessage(modeLabel());
    }

    private Component modeLabel() {
        return executionMode == PlayerExecutionMode.SMART_RECOMMENDED
                ? Component.translatable("screen.steve_create_agent.smart")
                : Component.literal(executionMode.name());
    }

    private void submit() {
        int amount = parsedQuantity();
        if (selected < 0 || selected >= filtered.size() || amount < 1) return;
        GoalWire goal = filtered.get(selected);
        if (!goal.available()) return;
        failureCode = "";
        PlayerWorkflowNetwork.createProject(new CreateProjectC2S(
                snapshot.terminalNonce(), goal.target(), amount,
                ProductionMode.ONCE, executionMode));
    }

    private int parsedQuantity() {
        try {
            int value = Integer.parseInt(quantity == null ? "" : quantity.getValue());
            return value >= 1 && value <= 64 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Replaces the list with what the server matched.
     *
     * <p>Only if the box still holds the query that was asked. A player types faster than
     * a round trip, so a stale reply arriving late would otherwise put back results for a
     * query they have already moved past.</p>
     */
    void showSearchResults(GoalListS2C message) {
        String currentQuery = search == null ? "" : search.getValue();
        if (!acceptsSearchResponse(snapshot.terminalNonce(), currentQuery, pendingQuery,
                message.terminalNonce(), message.query())) {
            return;
        }
        filtered.clear();
        filtered.addAll(message.goals());
        searchInFlight = false;
        selected = -1;
        scroll = 0;
        updateSelectState();
    }

    static boolean acceptsSearchResponse(
            long expectedNonce, String currentQuery, String pendingQuery,
            long responseNonce, String responseQuery) {
        if (expectedNonce != responseNonce) return false;
        String current = currentQuery.toLowerCase(Locale.ROOT).trim();
        String pending = pendingQuery.toLowerCase(Locale.ROOT).trim();
        String response = responseQuery.toLowerCase(Locale.ROOT).trim();
        return !current.isEmpty() && current.equals(pending) && pending.equals(response);
    }

    private void updateSelectState() {
        if (select != null) {
            select.active = selected >= 0 && selected < filtered.size()
                    && filtered.get(selected).available()
                    && parsedQuantity() == filtered.get(selected).verifiedQuantity();
            if (selected >= 0 && selected < filtered.size()
                    && parsedQuantity() > 0
                    && parsedQuantity() != filtered.get(selected).verifiedQuantity()) {
                // Says the number, not just the rule. Selecting a goal already fills the
                // field with the verified quantity, so this only appears once a player
                // has typed over it — at which point "not execution verified" leaves them
                // to guess what would be.
                failureCode = "TARGET_QUANTITY_NOT_EXECUTION_VERIFIED: "
                        + filtered.get(selected).verifiedQuantity();
            } else if (failureCode.startsWith("TARGET_QUANTITY_NOT_EXECUTION_VERIFIED")) {
                failureCode = "";
            }
        }
    }

    public void showServerFailure(String code) {
        searchDelayTicks = -1;
        searchInFlight = false;
        failureCode = code;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = width / 2 - 150;
        int firstY = LIST_TOP;
        if (button == 0 && mouseX >= left && mouseX < left + 300
                && mouseY >= firstY && mouseY < firstY + visibleRows() * ROW_HEIGHT) {
            int row = (int) ((mouseY - firstY) / ROW_HEIGHT);
            int index = scroll + row;
            if (index >= 0 && index < filtered.size()) {
                selected = index;
                quantity.setValue(Integer.toString(filtered.get(index).verifiedQuantity()));
                failureCode = filtered.get(index).available() ? "" : filtered.get(index).statusCode();
                updateSelectState();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maximum = Math.max(0, filtered.size() - visibleRows());
        scroll = Math.max(0, Math.min(maximum, scroll + (delta < 0 ? 1 : -1)));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int left = width / 2 - 150;
        int firstY = LIST_TOP;
        if (filtered.isEmpty() && !pendingQuery.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(searchInFlight
                            ? "screen.steve_create_agent.searching"
                            : "screen.steve_create_agent.no_search_results"),
                    width / 2, firstY + 8, 0xA0A0A0);
        }
        for (int row = 0; row < visibleRows(); row++) {
            int index = scroll + row;
            if (index >= filtered.size()) break;
            GoalWire goal = filtered.get(index);
            int y = firstY + row * ROW_HEIGHT;
            int color = index == selected ? 0x80508CC8 : 0x60303030;
            graphics.fill(left, y, left + 300, y + 20, color);
            ItemStack icon = BuiltInRegistries.ITEM.get(ResourceLocation.parse(goal.target()))
                    .getDefaultInstance();
            if (!icon.isEmpty()) graphics.renderItem(icon, left + 2, y + 2);
            int textColor = goal.available() ? 0xFFFFFF : 0x9A9A9A;
            graphics.drawString(font, label(goal), left + 22, y + 2, textColor);
            graphics.drawString(font, goal.target(), left + 22, y + 11, 0xA0A0A0, false);
            if (!goal.available()) {
                graphics.drawString(font, goal.statusCode(), left + 205, y + 6, 0xFF7777, false);
            }
        }
        if (selected >= 0 && selected < filtered.size()) {
            GoalWire goal = filtered.get(selected);
            graphics.drawString(font, Component.translatable("screen.steve_create_agent.goal_summary",
                    goal.capability(), goal.recipe(), goal.modules()), left, height - 82, 0xD8E8FF);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.material_summary", goal.inputs()),
                    left, height - 70, 0xD0D0D0);
        }
        graphics.drawString(font, Component.translatable("screen.steve_create_agent.quantity"),
                left + 258, 34, 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("screen.steve_create_agent.maintain_unavailable"),
                left, height - 62, 0x8A8A8A);
        if (!failureCode.isEmpty()) {
            graphics.drawCenteredString(font, failureCode, width / 2, height - 18, 0xFF6B6B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
