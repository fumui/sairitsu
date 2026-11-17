#include "vm/blocks.h"

#include <algorithm>
#include <iterator>

namespace sr::vm {
namespace {

using data::CallbackKind;

constexpr uint16_t kPreprocess = callback_bit(CallbackKind::Preprocess);
constexpr uint16_t kSpawnTime = callback_bit(CallbackKind::SpawnTime);
constexpr uint16_t kDespawnTime = callback_bit(CallbackKind::DespawnTime);
constexpr uint16_t kSpawnOrder = callback_bit(CallbackKind::SpawnOrder);
constexpr uint16_t kShouldSpawn = callback_bit(CallbackKind::ShouldSpawn);
constexpr uint16_t kInitialize = callback_bit(CallbackKind::Initialize);
constexpr uint16_t kUpdateSequential = callback_bit(CallbackKind::UpdateSequential);
constexpr uint16_t kTouch = callback_bit(CallbackKind::Touch);
constexpr uint16_t kUpdateParallel = callback_bit(CallbackKind::UpdateParallel);
constexpr uint16_t kTerminate = callback_bit(CallbackKind::Terminate);
constexpr uint16_t kUpdateSpawn = callback_bit(CallbackKind::UpdateSpawn);

#include "vm/blocks_watch.inc"
#include "vm/blocks_play.inc"

}  // namespace

std::span<const BlockDef> block_table(data::Mode mode) {
    return mode == data::Mode::Play ? std::span<const BlockDef>(kPlayBlocks)
                                    : std::span<const BlockDef>(kWatchBlocks);
}

const BlockDef* find_block(data::Mode mode, int32_t id) {
    auto table = block_table(mode);
    auto it = std::lower_bound(table.begin(), table.end(), id,
                               [](const BlockDef& b, int32_t v) { return b.id < v; });
    return it != table.end() && it->id == id ? &*it : nullptr;
}

}  // namespace sr::vm
