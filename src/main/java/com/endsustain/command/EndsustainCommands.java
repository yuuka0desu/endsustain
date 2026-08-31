package com.endsustain.command;

import com.endsustain.EndSustain;
import com.endsustain.item.NullVoidBladeItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 虚空断刃口令解锁指令：/endsustain unlock &lt;密钥&gt; */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EndsustainCommands {
    private EndsustainCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("endsustain")
                .then(Commands.literal("unlock")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    Entity entity = source.getEntity();
                                    String key = StringArgumentType.getString(context, "key");
                                    if (entity instanceof ServerPlayer player) {
                                        if (NullVoidBladeItem.tryUnlock(player, key)) {
                                            source.sendSuccess(() -> Component.literal("虚空断刃已解锁。"), false);
                                        } else {
                                            source.sendFailure(Component.literal("密钥错误。"));
                                        }
                                    }
                                    return 1;
                                }))));
    }
}
