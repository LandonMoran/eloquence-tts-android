// Snapshot reading order, kept separate from the bridge so it can be tested on
// the host: it is plain C++ with no Android or il2cpp dependency.
//
// The order is the PC mod's zone order (pet shop, food shop, team, actions,
// bullies; ShopNavigationManager.cs:256-268 binds S/F/T/D/V to exactly these
// zones), then top-to-bottom, left-to-right inside a zone.
//
// This used to be a lambda passed straight to std::sort that compared y only
// when the two values differed by more than 8 px and x otherwise. That is not a
// strict weak ordering: with a = (y 0, x 100), b = (y 6, x 50) and c = (y 12,
// x 10) it said b < a, c < b and a < c, a cycle. std::sort given an invalid
// comparator has undefined behaviour and can scramble the array, so the same
// shop screen could come back in a different order on the next poll: "Slot 2"
// changed meaning and TalkBack's focus ring jumped. Bucketing y into rows makes
// the key lexicographic, which is a total order.
#pragma once

#include <cmath>
#include <cstring>

namespace sapa11y_order {

/**
 * Row height used to group controls. Shop and team cards are laid out well over
 * 100 px apart vertically, so 16 px cannot merge two real rows, while a couple
 * of pixels of layout wobble stays inside one bucket. A larger bucket also
 * cannot make the key intransitive: it is a plain integer comparison.
 */
constexpr float ROW_PX = 16.0f;

/** The PC mod's zone order; everything else sorts after the shop zones. */
inline int zone_rank(const char* zone) {
    if (zone == nullptr || zone[0] == '\0') return 5;
    if (std::strcmp(zone, "petshop") == 0) return 0;
    if (std::strcmp(zone, "foodshop") == 0) return 1;
    if (std::strcmp(zone, "team") == 0) return 2;
    if (std::strcmp(zone, "actions") == 0) return 3;
    if (std::strcmp(zone, "bullies") == 0) return 4;
    return 5;
}

inline int row_bucket(float y) {
    return static_cast<int>(std::lround(y / ROW_PX));
}

/**
 * True when a is read before b: zone, then row, then x, then instance id.
 * The id tiebreak keeps controls that share a position in a fixed order
 * instead of an arbitrary one, so two snapshots of one screen agree.
 */
inline bool before(const char* zone_a, float y_a, float x_a, int id_a,
                   const char* zone_b, float y_b, float x_b, int id_b) {
    const int rank_a = zone_rank(zone_a);
    const int rank_b = zone_rank(zone_b);
    if (rank_a != rank_b) return rank_a < rank_b;
    const int row_a = row_bucket(y_a);
    const int row_b = row_bucket(y_b);
    if (row_a != row_b) return row_a < row_b;
    if (x_a != x_b) return x_a < x_b;
    return id_a < id_b;
}

}  // namespace sapa11y_order
