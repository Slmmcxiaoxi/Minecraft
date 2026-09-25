package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Test support: reads which slot the cursor is over.
 * <p>
 * The tooltip verification has to know whether the cursor really ended up on the
 * slot with the custom named item - otherwise a "tooltip was not translated"
 * finding could just mean the mouse was two pixels off. Read-only accessor.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("hoveredSlot")
	Slot aiTranslate$getHoveredSlot();

	@Accessor("leftPos")
	int aiTranslate$getLeftPos();

	@Accessor("topPos")
	int aiTranslate$getTopPos();
}
