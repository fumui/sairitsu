#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <vector>

#include "data/engine_data.h"

namespace sr::test {

// Builds an EngineModeData node graph by hand:
//   NodeBuilder b;
//   auto root = b("Add", 1, b("Get", 4000, 0));   // numbers become value nodes
//   int32_t entry = b.entry(root);                 // archetype "Test", updateSequential
class NodeBuilder {
public:
    struct Ref {
        int32_t index;
    };

    Ref value(float v) {
        data::Node n;
        n.is_value = true;
        n.value = v;
        data_.nodes.push_back(std::move(n));
        return Ref{int32_t(data_.nodes.size() - 1)};
    }

    Ref call(std::string_view func, const std::vector<Ref>& args = {}) {
        data::Node n;
        n.func = std::string(func);
        for (Ref r : args) n.args.push_back(r.index);
        data_.nodes.push_back(std::move(n));
        return Ref{int32_t(data_.nodes.size() - 1)};
    }

    template <class... A>
    Ref operator()(std::string_view func, A... args) {
        return call(func, std::vector<Ref>{ref(args)...});
    }

    // Makes `root` the `kind` callback of archetype `archetype` (created on first use); returns the entry.
    int32_t entry(Ref root, data::CallbackKind kind = data::CallbackKind::UpdateSequential,
                  const std::string& archetype = "Test") {
        data::Archetype* a = nullptr;
        for (auto& x : data_.archetypes)
            if (x.name == archetype) a = &x;
        if (a == nullptr) {
            data_.archetypes.emplace_back();
            a = &data_.archetypes.back();
            a->name = archetype;
        }
        a->callbacks[kind] = data::Callback{root.index, 0};
        return root.index;
    }

    // Makes `root` the global updateSpawn callback (watch).
    int32_t update_spawn(Ref root) {
        data_.update_spawn = root.index;
        return root.index;
    }

    data::EngineModeData& data() { return data_; }

private:
    static Ref ref(Ref r) { return r; }
    template <class T, class = std::enable_if_t<std::is_arithmetic_v<T>>>
    Ref ref(T v) {
        return value(float(v));
    }

    data::EngineModeData data_;
};

}  // namespace sr::test
