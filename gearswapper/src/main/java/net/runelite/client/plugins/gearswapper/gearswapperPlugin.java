/*
 * Copyright (c) 2019-2020, ganom <https://github.com/Ganom>
 * All rights reserved.
 * Licensed under GPL3, see LICENSE for the full scope.
 */

package net.runelite.client.plugins.gearswapper;

import com.google.common.base.Splitter;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuOpcode;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.kit.KitType;
import net.runelite.api.queries.NPCQuery;
import net.runelite.api.queries.PlayerQuery;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.plugins.gearswapper.utils.PrayerMap;
import net.runelite.client.plugins.gearswapper.utils.Spells;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.botutils.BotUtils;
import net.runelite.client.util.Clipboard;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.HotkeyListener;
import org.pf4j.Extension;

@Extension
@PluginDependency(BotUtils.class)
@PluginDescriptor(
	name = "<html>Gear Swapper <font size=\"\" color=\"green\"<b>BETA</font></b></html>",
	description = "Fastest Gear Swapping Ever",
	type = PluginType.UTILITY
)
@Slf4j
public class gearswapperPlugin extends Plugin
{
	private static final Splitter NEWLINE_SPLITTER = Splitter
		.on("\n")
		.omitEmptyStrings()
		.trimResults();

	@Inject
	private Client client;

	@Inject
	private BotUtils utils;

	@Inject
	private KeyManager keyManager;

	@Inject
	private gearswapperConfiguration config;

	@Inject
	private ItemManager itemManager;

	private BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
	private ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1, 25, TimeUnit.SECONDS, queue,
		new ThreadPoolExecutor.DiscardPolicy());

	MenuEntry targetMenu;
	private Actor lastTarget;

	@Provides
	gearswapperConfiguration provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(gearswapperConfiguration.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			keyManager.unregisterKeyListener(one);
			keyManager.unregisterKeyListener(two);
			keyManager.unregisterKeyListener(three);
			return;
		}
		keyManager.registerKeyListener(one);
		keyManager.registerKeyListener(two);
		keyManager.registerKeyListener(three);
	}
	@Override
	protected void startUp()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			targetMenu = null;
			keyManager.registerKeyListener(one);
			keyManager.registerKeyListener(two);
			keyManager.registerKeyListener(three);
		}
	}

	@Override
	protected void shutDown()
	{
		keyManager.registerKeyListener(one);
		keyManager.registerKeyListener(two);
		keyManager.registerKeyListener(three);
	}

	private final HotkeyListener one = new HotkeyListener(() -> config.customOne())
	{
		@Override
		public void hotkeyPressed()
		{
			decode(config.gearone());
			utils.sendGameMessage("Gear Swap One");
		}
	};

	private final HotkeyListener two = new HotkeyListener(() -> config.customTwo())
	{
		@Override
		public void hotkeyPressed()
		{
			decode(config.geartwo());
			utils.sendGameMessage("Gear Swap Two");
		}
	};

	private final HotkeyListener three = new HotkeyListener(() -> config.customThree())
	{
		@Override
		public void hotkeyPressed()
		{
			decode(config.gearthree());
			utils.sendGameMessage("Gear Swap Three");
		}
	};

	@Subscribe
	private void onInteractingChanged(InteractingChanged event) {
		try {
			if (event.getSource() instanceof Player) {
				Player localPlayer = this.client.getLocalPlayer();
				Player sourcePlayer = (Player)event.getSource();
				Actor targetActor = event.getTarget();
				if (localPlayer == sourcePlayer && targetActor != null) {
					if (this.lastTarget != targetActor) {
						this.lastTarget = targetActor;
					}
				}
			}
		} catch (Exception var5) {
			var5.printStackTrace();
		}

	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equalsIgnoreCase("copycs"))
		{
			final ItemContainer e = client.getItemContainer(InventoryID.EQUIPMENT);

			if (e == null)
			{
				log.error("CopyCS: Can't find equipment container.");
				return;
			}

			final StringBuilder sb = new StringBuilder();

			for (Item item : e.getItems())
			{
				if (item.getId() == -1 || item.getId() == 0)
				{
					continue;
				}

				sb.append(item.getId());
				sb.append(":");
				sb.append("Equip");
				sb.append("\n");
			}

			final String string = sb.toString();
			Clipboard.store(string);
		}
	}

	private void decode(String string)
	{
		final Map<String, String> map = new LinkedHashMap<>();
		final List<WidgetItem> equipitemids = new ArrayList<>();
		final List<WidgetItem> foodids = new ArrayList<>();
		final List<WidgetItem> inventoryItems = new ArrayList<>();
		final List<Integer> playerEquipment = new ArrayList<>();
		WidgetItem dropitem = null;
		final Iterable<String> tmp = NEWLINE_SPLITTER.split(string);
		for (String s : tmp)
		{
			if (s.startsWith("//"))
			{
				continue;
			}
			String[] split = s.split(":");
			try
			{
				map.put(split[0], split[1]);
			}
			catch (IndexOutOfBoundsException e)
			{
				log.error("Decode: Invalid Syntax in decoder.");
				dispatchError("Invalid Syntax in decoder.");
				return;
			}
		}

		for (Map.Entry<String, String> entry : map.entrySet())
		{
			String param = entry.getKey();
			String command = entry.getValue().toLowerCase();

			switch (command)
			{
				case "equip":
				{
					WidgetItem item = utils.getInventoryWidgetItem(Integer.parseInt(param));
					if (item == null)
					{
						log.debug("Equip: Can't find valid bounds for param {}.", param);
						continue;
					}
					equipitemids.add(item);
				}
				break;
				case "remove":
				{
					final Player p = client.getLocalPlayer();

					for (KitType kitType : KitType.values())
					{
						if (kitType == KitType.RING || kitType == KitType.AMMUNITION ||
							p.getPlayerAppearance() == null)
						{
							continue;
						}

						final int itemId = p.getPlayerAppearance().getEquipmentId(kitType);

						if (itemId != -1 && itemId == Integer.parseInt(param))
						{
							playerEquipment.add(kitType.getWidgetInfo().getId());
						}
					}
				}
				break;
				case "drop":
				{
					dropitem = utils.getInventoryWidgetItem(Integer.parseInt(param));
					inventoryItems.addAll(utils.getAllInventoryItems());
				}
				break;
				case "eat":
				{
					WidgetItem item = utils.getInventoryWidgetItem(Integer.parseInt(param));
					if (item == null)
					{
						log.debug("eat: Can't find valid bounds for param {}.", param);
						continue;
					}
					foodids.add(item);
				}
				break;
				case "pray":
				{
					final WidgetInfo info = getPrayerWidgetInfo(param);

					if (info == null)
					{
						log.debug("Prayer: Can't find valid widget info for param {}.", param);
						continue;
					}

					final Widget widget = client.getWidget(info);

					if (widget == null)
					{
						log.debug("Prayer: Can't find valid widget for param {}.", param);
						continue;
					}
					utils.setMenuEntry(new MenuEntry("", "", 1, 57, widget.getItemId(), widget.getId(),
						false));
					utils.click(client.getMouseCanvasPosition());
				}
				break;
				case "castspell":
				{
					final WidgetInfo info = getSpellWidgetInfo(param);

					if (info == null)
					{
						log.debug("Cast: Can't find valid widget info for param {}.", param);
						continue;
					}

					final Widget widget = client.getWidget(info);

					if (widget == null)
					{
						log.debug("Cast: Can't find valid widget for param {}.", param);
						continue;
					}
					utils.setMenuEntry(new MenuEntry("", "", 1, 57, widget.getItemId(), widget.getId(),
						false));
					utils.click(client.getMouseCanvasPosition());
				}
				break;
				case "leftclickcast":
				{
					final WidgetInfo info = getSpellWidgetInfo(param);

					if (info == null)
					{
						log.debug("Cast: Can't find valid widget info for param {}.", param);
						continue;
					}

					final Widget widget = client.getWidget(info);

					if (widget == null)
					{
						log.debug("Cast: Can't find valid widget for param {}.", param);
						continue;
					}
					utils.setMenuEntry(new MenuEntry("", "", 1, 25, widget.getItemId(), widget.getId(),
						false));
					utils.click(client.getMouseCanvasPosition());
				}
				break;
				case "enable":
				{
					final Widget widget = client.getWidget(593, 36);
					if (widget == null)
					{
						log.debug("Spec: Can't find valid widget");
						continue;
					}
					utils.setMenuEntry(new MenuEntry("", "", 1, 57, -1, widget.getId(),
						false));
					utils.click(client.getMouseCanvasPosition());
				}
				break;
				case "enableon":
				{
					final Widget widget = client.getWidget(593, 36);
					if (widget == null)
					{
						log.debug("Spec: Can't find valid widget");
						continue;
					}
					if (client.getVar(VarPlayer.SPECIAL_ATTACK_ENABLED) != 1)
					{
						utils.setMenuEntry(new MenuEntry("", "", 1, 57, -1, widget.getId(),
							false));
						utils.click(client.getMouseCanvasPosition());
						continue;
					}
				}
				break;
				case "hitlasttarget":
				{
					final Actor lastTarget = getLastTarget();
					if (lastTarget == null)
					{
						continue;
					}
					if (lastTarget instanceof NPC)
					{
						final NPC npcTarget = new NPCQuery().idEquals(new int[]{((NPC) lastTarget).getId()}).result(client).first();
						if (npcTarget == null)
						{
							continue;
						}
						utils.setMenuEntry(new MenuEntry("", "", ((NPC) lastTarget).getIndex(), client.isSpellSelected() ? MenuOpcode.SPELL_CAST_ON_NPC.getId() : MenuOpcode.NPC_SECOND_OPTION.getId(), 0, 0,
							false));
						utils.click(client.getMouseCanvasPosition());
					}
					else
					{
						if (!(lastTarget instanceof Player))
						{
							continue;
						}
						final Player playerTarget = new PlayerQuery().nameEquals(new String[]{lastTarget.getName()}).result(client).first();
						if (playerTarget == null)
						{
							continue;
						}
						utils.setMenuEntry(new MenuEntry("Attack", "<col=ffffff>" + playerTarget.getName() + "<col=ff3000>  (level-" + playerTarget.getCombatLevel() + ")", playerTarget.getPlayerId(), client.isSpellSelected() ? MenuOpcode.SPELL_CAST_ON_PLAYER.getId() : MenuOpcode.PLAYER_SECOND_OPTION.getId(), 0, 0,
							false));
						utils.click(client.getMouseCanvasPosition());
					}
				}
				break;
				case "movetotarget":
				{
					final Actor lastTarget = getLastTarget();
					if (lastTarget == null)
					{
						continue;
					}
					if (!(lastTarget instanceof Player))
					{
						continue;
					}
					final Player playerTarget = new PlayerQuery().nameEquals(new String[]{lastTarget.getName()}).result(client).first();
					if (playerTarget == null)
					{
						continue;
					}
					utils.setMenuEntry(new MenuEntry("Follow", "<col=ff0000>" + playerTarget.getName() + "<col=ff00>  (level-" + playerTarget.getCombatLevel() + ")", playerTarget.getPlayerId(), MenuOpcode.PLAYER_THIRD_OPTION.getId(), 0, 0,
						false));
					utils.click(client.getMouseCanvasPosition());
				}
				break;
			}
		}

		WidgetItem finalDropitem = dropitem;

		executorService.submit(() ->
		{
			for (WidgetItem item : foodids)
			{
				try
				{
					utils.setMenuEntry(new MenuEntry("", "", item.getId(), MenuOpcode.ITEM_FIRST_OPTION.getId(), item.getIndex(), WidgetInfo.INVENTORY.getId(),
						false));
					utils.click(item.getCanvasBounds());
					Thread.sleep(getMillis());
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
			for (WidgetItem item : equipitemids)
			{
				try
				{
					utils.setMenuEntry(new MenuEntry("", "", item.getId(), MenuOpcode.ITEM_SECOND_OPTION.getId(), item.getIndex(), WidgetInfo.INVENTORY.getId(),
						false));
					utils.click(item.getCanvasBounds());
					Thread.sleep(getMillis());
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
			for (Integer GearID : playerEquipment)
			{
				try
				{
					utils.setMenuEntry(new MenuEntry("", "", 1, 57, -1, GearID,
						false));
					utils.click(client.getMouseCanvasPosition());
					Thread.sleep(getMillis());
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
					for (WidgetItem item : inventoryItems)
					{
						if (finalDropitem.getId() == item.getId()) //6512 is empty widget slot
						{
							utils.setMenuEntry(new MenuEntry("", "", item.getId(), MenuOpcode.ITEM_FIFTH_OPTION.getId(), item.getIndex(), WidgetInfo.INVENTORY.getId(),
								false));
							utils.click(item.getCanvasBounds());
							try
							{
								Thread.sleep((int) getMillis());
							}
							catch (InterruptedException e)
							{
								e.printStackTrace();
							}
						}
					}

		});
	}
	private long getMillis()
	{
		return (long) (Math.random() * config.randLow() + config.randHigh());
	}

	public Actor getLastTarget() {
		return this.lastTarget;
	}

	public WidgetInfo getPrayerWidgetInfo(String spell)
	{
		return PrayerMap.getWidget(spell);
	}

	public WidgetInfo getSpellWidgetInfo(String spell)
	{
		return Spells.getWidget(spell);
	}

	private void dispatchError(String error)
	{
		String str = ColorUtil.wrapWithColorTag("Gear Swapper", Color.MAGENTA)
			+ " has encountered an "
			+ ColorUtil.wrapWithColorTag("error", Color.RED)
			+ ": "
			+ error;

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", str, null);
	}

}
