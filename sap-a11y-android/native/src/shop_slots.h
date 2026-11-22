// Which of HangarMain's own click handlers owns a slot, by container.
//
// The game routes a slot click to HangarMain.HandleMinionShopClick for the pet
// shop, HandleSpellShopClick for the food shop and HandleMinionArmyClick for
// the team. That is the path the PC mod's PerformAction drives, and the
// signatures are asserted in tests/reference/check.py, so the names here are
// the reference-checked ones rather than guesses.
//
// Kept free of il2cpp types so the mapping itself is tested on the host
// (tests/native/test_shop_slots.cpp): a wrong pair of names here silently
// breaks buying while every other check stays green.
#ifndef SAPA11Y_SHOP_SLOTS_H
#define SAPA11Y_SHOP_SLOTS_H

namespace sapa11y {

enum ShopSlotZone {
    kSlotNone = 0,
    kSlotPetShop = 1,
    kSlotSpellShop = 2,
    kSlotTeam = 3,
};

struct ShopSlotHandler {
    const char* holder_field;  // HangarMain field holding the container
    const char* method;        // the container's (container, space) click handler
    const char* container_type;
};

// Returns nullptr for kSlotNone, so a control that belongs to no container
// cannot be handed to a handler at all.
inline const ShopSlotHandler* shop_slot_handler(int zone) {
    static const ShopSlotHandler pet_shop{"MinionShop", "HandleMinionShopClick", "MinionShop"};
    static const ShopSlotHandler spell_shop{"SpellShop", "HandleSpellShopClick", "SpellShop"};
    static const ShopSlotHandler team{"MinionArmy", "HandleMinionArmyClick", "MinionArmy"};
    switch (zone) {
        case kSlotPetShop: return &pet_shop;
        case kSlotSpellShop: return &spell_shop;
        case kSlotTeam: return &team;
        default: return nullptr;
    }
}

}  // namespace sapa11y

#endif  // SAPA11Y_SHOP_SLOTS_H
