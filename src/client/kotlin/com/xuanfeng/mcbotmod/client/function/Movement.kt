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

package com.xuanfeng.mcbotmod.client.function

import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

object Movement {
    @JvmStatic var forward = false
    @JvmStatic var backward = false
    @JvmStatic var left = false
    @JvmStatic var right = false
    @JvmStatic var jump = false
    @JvmStatic var sneak = false

    @JvmStatic
    fun set(
        forward: Boolean? = null,
        backward: Boolean? = null,
        left: Boolean? = null,
        right: Boolean? = null,
        jump: Boolean? = null,
        sneak: Boolean? = null,
    ) {
        if (forward != null) this.forward = forward
        if (backward != null) this.backward = backward
        if (left != null) this.left = left
        if (right != null) this.right = right
        if (jump != null) this.jump = jump
        if (sneak != null) this.sneak = sneak
    }

    @JvmStatic
    fun stop() {
        forward = false
        backward = false
        left = false
        right = false
        jump = false
        sneak = false
    }

    @JvmStatic
    fun isRequested(key: KeyMapping): Boolean {
        val opts = Minecraft.getInstance().options

        return when (key) {
            opts.keyUp -> forward
            opts.keyDown -> backward
            opts.keyLeft -> left
            opts.keyRight -> right
            opts.keyJump -> jump
            opts.keyShift -> sneak
            else -> false
        }
    }
}