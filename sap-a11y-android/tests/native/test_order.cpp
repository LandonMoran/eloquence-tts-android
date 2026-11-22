// Host test for the snapshot reading order (native/src/order.h).
//
// The order decides what "Slot 1" means in every shop and board announcement,
// so it has to be a total order: std::sort with an intransitive comparator is
// undefined behaviour, and the previous one was intransitive for controls whose
// y values sat within 8 px of each other but whose x order disagreed with y.
#include "../../native/src/order.h"

#include <cstdio>
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

bool before(const char* za, float ya, float xa, int ida,
            const char* zb, float yb, float xb, int idb) {
    return sapa11y_order::before(za, ya, xa, ida, zb, yb, xb, idb);
}

// The old comparator, kept here to prove the case that made it invalid.
bool old_before(float ya, float xa, float yb, float xb) {
    if (std::fabs(ya - yb) > 8.0f) return ya < yb;
    return xa < xb;
}

void zonesComeInThePcModOrder() {
    const char* zones[] = {"petshop", "foodshop", "team", "actions", "bullies", ""};
    for (int i = 0; i < 5; ++i) {
        check("zone order is the PC mod's S/F/T/D order",
              before(zones[i], 5000, 5000, 1, zones[i + 1], 0, 0, 2),
              zones[i]);
        check("and the reverse is not also true",
              !before(zones[i + 1], 0, 0, 2, zones[i], 5000, 5000, 1), zones[i]);
    }
}

void slotsInARowReadLeftToRight() {
    // Three shop cards on one visual row.
    check("same row, lower x reads first",
          before("petshop", 700, 300, 1, "petshop", 700, 440, 2), "300 then 440");
    // A two-pixel wobble in y must not reorder the row by y.
    check("a small y wobble does not break the row",
          before("petshop", 702, 300, 1, "petshop", 698, 440, 2), "wobble");
}

void rowsReadTopToBottom() {
    check("a higher row reads first",
          before("team", 1500, 900, 1, "team", 1700, 200, 2), "1500 then 1700");
}

void theOldComparatorWasCyclic() {
    // a = (0, 100), b = (6, 50), c = (12, 10): b < a, c < b, a < c.
    const bool b_a = old_before(6, 50, 0, 100);
    const bool c_b = old_before(12, 10, 6, 50);
    const bool a_c = old_before(0, 100, 12, 10);
    check("the old comparator really did cycle", b_a && c_b && a_c,
          "a/b/c triple");

    // The same three under the row-bucket key are ordered, not cyclic.
    const bool nb_a = before("petshop", 6, 50, 2, "petshop", 0, 100, 1);
    const bool nc_b = before("petshop", 12, 10, 3, "petshop", 6, 50, 2);
    const bool na_c = before("petshop", 0, 100, 1, "petshop", 12, 10, 3);
    check("row buckets remove the cycle", !(nb_a && nc_b && na_c), "cycle");
}

void theOrderIsATotalOrderOnACraftedScreen() {
    struct Item { const char* zone; float y; float x; int id; };
    const Item items[] = {
            {"petshop", 700, 440, 12}, {"petshop", 702, 300, 11},
            {"petshop", 698, 300, 13}, {"foodshop", 950, 300, 21},
            {"team", 1500, 200, 31},   {"team", 1500, 200, 30},
            {"actions", 2100, 900, 41}, {"", 80, 60, 51},
            {"", 82, 160, 52},
    };
    const int n = static_cast<int>(sizeof(items) / sizeof(items[0]));
    bool irreflexive = true;
    bool transitive = true;
    bool total = true;
    for (int i = 0; i < n; ++i) {
        if (before(items[i].zone, items[i].y, items[i].x, items[i].id,
                   items[i].zone, items[i].y, items[i].x, items[i].id)) {
            irreflexive = false;
        }
        for (int j = 0; j < n; ++j) {
            for (int k = 0; k < n; ++k) {
                const bool ij = before(items[i].zone, items[i].y, items[i].x, items[i].id,
                                       items[j].zone, items[j].y, items[j].x, items[j].id);
                const bool jk = before(items[j].zone, items[j].y, items[j].x, items[j].id,
                                       items[k].zone, items[k].y, items[k].x, items[k].id);
                const bool ik = before(items[i].zone, items[i].y, items[i].x, items[i].id,
                                       items[k].zone, items[k].y, items[k].x, items[k].id);
                if (ij && jk && !ik) transitive = false;
            }
        }
        for (int j = i + 1; j < n; ++j) {
            const bool ij = before(items[i].zone, items[i].y, items[i].x, items[i].id,
                                   items[j].zone, items[j].y, items[j].x, items[j].id);
            const bool ji = before(items[j].zone, items[j].y, items[j].x, items[j].id,
                                   items[i].zone, items[i].y, items[i].x, items[i].id);
            if (ij == ji) total = false;  // neither before, or both
        }
    }
    check("the order is irreflexive", irreflexive, "a < a");
    check("the order is transitive", transitive, "a<b<c");
    check("the order is total on distinct controls", total, "a vs b");

    // The two team slots share a position; the id decides, deterministically.
    check("equal positions fall back to the instance id",
          before("team", 1500, 200, 30, "team", 1500, 200, 31), "ids 30/31");
}

}  // namespace

int main() {
    zonesComeInThePcModOrder();
    slotsInARowReadLeftToRight();
    rowsReadTopToBottom();
    theOldComparatorWasCyclic();
    theOrderIsATotalOrderOnACraftedScreen();

    std::printf("\n%d passed, %zu failed\n", passed, failures.size());
    return failures.empty() ? 0 : 1;
}
