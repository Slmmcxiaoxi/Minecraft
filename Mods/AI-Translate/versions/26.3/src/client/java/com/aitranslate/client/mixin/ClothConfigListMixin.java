package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.shedaniel.clothconfig2.gui.widget.DynamicEntryListWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Makes a click in the Cloth config list reach the entry that is <em>drawn</em> at that
 * point, even when Cloth's own routing does not find it.
 * <p>
 * Seventeenth feedback round, 1.1 ("fields inside collapsible groups cannot be typed
 * into"). What the probe found:
 *
 * <ul>
 * <li>a text field inside a collapsed group is reachable as soon as the group is expanded
 * and the field is inside the visible part of the list - the click is delivered, the field
 * takes the focus and receives characters (measured: {@code dispatch=true focused=true},
 * three characters accepted);</li>
 * <li>what does <em>not</em> work is a field that sits outside the list's own rectangle:
 * in a small window the expanded groups push the fields below {@code list.bottom}, and
 * Cloth's dispatch starts with {@code getChildAt}, which asks the list whether the point
 * is inside it - so the click is dropped before any entry is asked. (Measured in the same
 * run: fields at y=228/340 with {@code list.bottom=208}.)</li>
 * </ul>
 *
 * Rather than changing Cloth, the click that Cloth's routing dropped is offered to the
 * entries themselves, innermost first: an entry that reports the point as inside it and
 * accepts the click gets it, exactly like a click that Cloth routed normally. Everything
 * that Cloth already handled returns early, so no click is ever delivered twice.
 * <p>
 * The hook only runs when the list's own {@code mouseClicked} returned {@code false}, i.e.
 * when nothing in the list wanted the click - it cannot change the behaviour of a click
 * that already worked.
 */
@Mixin(DynamicEntryListWidget.class)
public abstract class ClothConfigListMixin {

	@Inject(method = "mouseClicked", at = @At("RETURN"), cancellable = true)
	private void aiTranslate$deliverToDrawnEntry(MouseButtonEvent event, boolean doubled,
			CallbackInfoReturnable<Boolean> info) {
		if (info.getReturnValueZ()) {
			return;
		}
		if (aiTranslate$offer(aiTranslate$listChildren(), event, doubled, 0)) {
			info.setReturnValue(true);
		}
	}

	/**
	 * The children of the list widget (its entries).
	 * <p>
	 * The name is prefixed and deliberately <em>not</em> {@code children}: Mixin treats a
	 * member with the same name and descriptor as the target as an overwrite, and
	 * {@code DynamicEntryListWidget#children()} is public - the first version of this mixin
	 * was rejected at runtime with "PRIVATE overwrite method children cannot reduce
	 * visibility of PUBLIC target method" (found in the round 17 verification log).
	 */
	private List<? extends GuiEventListener> aiTranslate$listChildren() {
		return ((DynamicEntryListWidget<?>) (Object) this).children();
	}

	/**
	 * Offers the click to the entries under the cursor, descending into containers.
	 *
	 * @return true when an entry accepted the click
	 */
	private static boolean aiTranslate$offer(List<? extends GuiEventListener> children, MouseButtonEvent event,
			boolean doubled, int depth) {
		if (children == null || depth > 6) {
			return false;
		}
		for (GuiEventListener child : children) {
			if (child == null || !child.isMouseOver(event.x(), event.y())) {
				continue;
			}
			if (child instanceof ContainerEventHandler container
					&& aiTranslate$offer(container.children(), event, doubled, depth + 1)) {
				return true;
			}
			if (child.mouseClicked(event, doubled)) {
				return true;
			}
		}
		return false;
	}
}
