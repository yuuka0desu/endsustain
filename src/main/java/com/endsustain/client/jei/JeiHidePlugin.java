package com.endsustain.client.jei;

import com.endsustain.EndSustain;
import com.endsustain.item.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** JEI 隐藏：虚空断刃在 JEI 中不可见，唯一获取途径为游戏内口令解锁。 */
@JeiPlugin
public class JeiHidePlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(EndSustain.MOD_ID, "hide_void_breaker");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        jeiRuntime.getIngredientManager().removeIngredientsAtRuntime(
                VanillaTypes.ITEM_STACK,
                List.of(new ItemStack(ModItems.NULL_VOID_BLADE.get())));
    }
}
