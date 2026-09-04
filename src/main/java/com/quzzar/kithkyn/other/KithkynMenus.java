package com.quzzar.kithkyn.other;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.PersonContainer;
import com.quzzar.kithkyn.menu.MarketMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class KithkynMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Kithkyn.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<PersonContainer>> PERSON_MENU = MENUS.register("person_menu",
            () -> IMenuTypeExtension.create(PersonContainer::fromNetwork));

    /** The market stall, which is a real container so its slots behave like slots. */
    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> MARKET = MENUS.register("market",
            () -> IMenuTypeExtension.create(MarketMenu::new));
}
