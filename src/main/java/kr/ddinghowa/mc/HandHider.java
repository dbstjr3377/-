package kr.ddinghowa.mc;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 플래시 게임을 보는 동안 플레이어 손이 보이지 않게 한다.
 *
 * <p>주 손에는 리소스팩의 빈 모델(ddinghowa:invisible)을 쓰는 아이템을 쥐여 주고 보조 손은 비운다.
 * 원래 들고 있던 아이템은 플레이어 데이터(PDC)에 저장해 두었다가 게임이 끝나면(또는 다시 접속하면) 돌려준다.</p>
 */
final class HandHider {

    static final NamespacedKey SAVED_MAIN = new NamespacedKey("ddinghowa", "saved_main");
    static final NamespacedKey SAVED_OFF = new NamespacedKey("ddinghowa", "saved_off");
    static final NamespacedKey SAVED_SLOT = new NamespacedKey("ddinghowa", "saved_slot");
    static final NamespacedKey INVISIBLE = new NamespacedKey("ddinghowa", "invisible");

    private HandHider() {
    }

    static ItemStack invisibleItem() {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(INVISIBLE);
        m.itemName(Component.empty());
        m.setHideTooltip(true);
        m.getPersistentDataContainer().set(INVISIBLE, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    static boolean isInvisibleItem(ItemStack s) {
        return s != null && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(INVISIBLE, PersistentDataType.BYTE);
    }

    static void hide(Player p) {
        PlayerInventory inv = p.getInventory();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)) {
            pdc.set(SAVED_MAIN, PersistentDataType.BYTE_ARRAY, bytes(inv.getItemInMainHand()));
            pdc.set(SAVED_OFF, PersistentDataType.BYTE_ARRAY, bytes(inv.getItemInOffHand()));
            pdc.set(SAVED_SLOT, PersistentDataType.INTEGER, inv.getHeldItemSlot());
        }
        inv.setItemInMainHand(invisibleItem());
        inv.setItemInOffHand(null);
    }

    /** 저장해 둔 손 아이템을 돌려준다 (서버가 꺼졌다 켜져도 접속 시 복구) */
    static void restore(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)) return;
        PlayerInventory inv = p.getInventory();
        Integer slot = pdc.get(SAVED_SLOT, PersistentDataType.INTEGER);
        if (slot != null && isInvisibleItem(inv.getItem(slot))) inv.setItem(slot, fromBytes(pdc.get(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)));
        for (int i = 0; i < inv.getSize(); i++) {
            if (isInvisibleItem(inv.getItem(i))) inv.setItem(i, null);
        }
        ItemStack off = fromBytes(pdc.get(SAVED_OFF, PersistentDataType.BYTE_ARRAY));
        if (off != null) {
            if (inv.getItemInOffHand().getType().isAir()) inv.setItemInOffHand(off);
            else inv.addItem(off);
        }
        pdc.remove(SAVED_MAIN);
        pdc.remove(SAVED_OFF);
        pdc.remove(SAVED_SLOT);
    }

    private static byte[] bytes(ItemStack s) {
        return s == null || s.getType().isAir() ? new byte[0] : s.serializeAsBytes();
    }

    private static ItemStack fromBytes(byte[] b) {
        return b == null || b.length == 0 ? null : ItemStack.deserializeBytes(b);
    }
}
