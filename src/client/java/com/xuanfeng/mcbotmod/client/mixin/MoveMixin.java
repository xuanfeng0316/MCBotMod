/*
 * Copyright (C) 2026 xuanfeng0316
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xuanfeng.mcbotmod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xuanfeng.mcbotmod.client.function.Movement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyboardInput.class)
public abstract class MoveMixin {

    static {
        //System.out.println("[MCBotMod] MoveMixin loaded!");
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/KeyMapping;isDown()Z"
            )
    )
    private boolean wrapIsDown(
            KeyMapping instance,
            Operation<Boolean> original
    ) {
        boolean playerPressed = original.call(instance);
        boolean botPressed = Movement.isRequested(instance);
        boolean finalPressed = playerPressed || botPressed;

        /*
        System.out.println(
                "[MCBotMod Input] "
                        + "key=" + instance.getName()
                        + " player=" + playerPressed
                        + " bot=" + botPressed
                        + " final=" + finalPressed
        );
        */

        return finalPressed;
    }
}