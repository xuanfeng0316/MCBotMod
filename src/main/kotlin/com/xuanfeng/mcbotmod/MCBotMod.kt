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

package com.xuanfeng.mcbotmod

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object MCBotMod : ModInitializer {
	const val MOD_ID: String = "mcbotmod"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		LOGGER.info("Mc Bot Mod Starting")
		LOGGER.info("Mc Bot Mod By xuanfeng0316")
	}

	fun id(path: String): Identifier
			= Identifier.fromNamespaceAndPath(MOD_ID, path)
}