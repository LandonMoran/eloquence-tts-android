// Host test for the shop slot -> HangarMain handler map (native/src/shop_slots.h).
//
// The bridge used to rely on taps alone for buying and placing, and a synthetic
// tap on this game's custom controls can be consumed without the handler
// running. The direct route hands the slot to HangarMain's own click handler;
// the mapping below is what decides which one, so a swapped or misspelled pair
// would break buying with every signature check still green.
#include "../../native/src/shop_slots.h"

#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int passed = 0;
std::vector<const char*> failures;

void check(const char* what, bool ok, const char* detail) {
    if (ok) {
        ++passed;
    } else {
        std::printf("FAILED: %s (%s)\n", what, detail);
        failures.push_back(what);
    }
}

bool same(const char* a, const char* b) {
    return a != nullptr && b != nullptr && std::strcmp(a, b) == 0;
}

void eachZoneHasItsOwnHandler() {
    using namespace sapa11y;
    const ShopSlotHandler* pet = shop_slot_handler(kSlotPetShop);
    const ShopSlotHandler* spell = shop_slot_handler(kSlotSpellShop);
    const ShopSlotHandler* team = shop_slot_handler(kSlotTeam);
    check("pet shop has a handler", pet != nullptr, "pet");
    check("food shop has a handler", spell != nullptr, "spell");
    check("team has a handler", team != nullptr, "team");
    if (pet == nullptr || spell == nullptr || team == nullptr) return;

    check("pet shop hands HangarMain.MinionShop",
          same(pet->holder_field, "MinionShop"), pet->holder_field);
    check("pet shop clicks through HandleMinionShopClick",
          same(pet->method, "HandleMinionShopClick"), pet->method);
    check("pet shop handler's first argument is a MinionShop",
          same(pet->container_type, "MinionShop"), pet->container_type);

    check("food shop hands HangarMain.SpellShop",
          same(spell->holder_field, "SpellShop"), spell->holder_field);
    check("food shop clicks through HandleSpellShopClick",
          same(spell->method, "HandleSpellShopClick"), spell->method);
    check("food shop handler's first argument is a SpellShop",
          same(spell->container_type, "SpellShop"), spell->container_type);

    check("team hands HangarMain.MinionArmy",
          same(team->holder_field, "MinionArmy"), team->holder_field);
    check("team clicks through HandleMinionArmyClick",
          same(team->method, "HandleMinionArmyClick"), team->method);
    check("team handler's first argument is a MinionArmy",
          same(team->container_type, "MinionArmy"), team->container_type);
}

void handlersDoNotOverlap() {
    using namespace sapa11y;
    const int zones[] = {kSlotPetShop, kSlotSpellShop, kSlotTeam};
    for (int i = 0; i < 3; ++i) {
        for (int j = i + 1; j < 3; ++j) {
            const ShopSlotHandler* a = shop_slot_handler(zones[i]);
            const ShopSlotHandler* b = shop_slot_handler(zones[j]);
            check("two containers never share a handler",
                  a != nullptr && b != nullptr &&
                      !same(a->method, b->method) && !same(a->holder_field, b->holder_field),
                  "zones");
        }
    }
}

void unknownZoneIsRefused() {
    check("no container means no handler", sapa11y::shop_slot_handler(sapa11y::kSlotNone) == nullptr,
          "none");
    check("an out-of-range zone is refused too", sapa11y::shop_slot_handler(99) == nullptr, "99");
}

}  // namespace

int main() {
    eachZoneHasItsOwnHandler();
    handlersDoNotOverlap();
    unknownZoneIsRefused();
    std::printf("\n%d passed, %d failed\n", passed, static_cast<int>(failures.size()));
    for (const char* f : failures) std::printf("  - %s\n", f);
    return failures.empty() ? 0 : 1;
}
