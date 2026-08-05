package com.billy65536.chunkscanner.debugger;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CsDebuggerMod implements ClientModInitializer {
	public static final String MOD_ID = "chunkscanner-debugger";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {


		LOGGER.info("Hello Fabric world!");
	}
}
