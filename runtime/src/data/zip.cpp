#include "data/zip.h"

#include <cstring>

#include "data/load_error.h"
#include "miniz.h"
#include "miniz_zip.h"

namespace sr::data {

namespace {

struct Reader {
    mz_zip_archive zip;
    bool open = false;
    Reader() { std::memset(&zip, 0, sizeof zip); }
    ~Reader() {
        if (open) mz_zip_reader_end(&zip);
    }
};

}  // namespace

std::map<std::string, std::vector<uint8_t>> unzip(std::span<const uint8_t> bytes, size_t max_entry) {
    Reader reader;
    if (!mz_zip_reader_init_mem(&reader.zip, bytes.data(), bytes.size(), 0)) {
        throw LoadError("zip: not a valid archive");
    }
    reader.open = true;
    std::map<std::string, std::vector<uint8_t>> out;
    const mz_uint count = mz_zip_reader_get_num_files(&reader.zip);
    for (mz_uint i = 0; i < count; ++i) {
        if (mz_zip_reader_is_file_a_directory(&reader.zip, i)) continue;
        mz_zip_archive_file_stat stat;
        if (!mz_zip_reader_file_stat(&reader.zip, i, &stat)) throw LoadError("zip: bad entry");
        if (stat.m_uncomp_size > max_entry) {
            throw LoadError(std::string("zip: entry '") + stat.m_filename +
                            "' exceeds the size limit");
        }
        std::vector<uint8_t> data(static_cast<size_t>(stat.m_uncomp_size));
        if (!data.empty() &&
            !mz_zip_reader_extract_to_mem(&reader.zip, i, data.data(), data.size(), 0)) {
            throw LoadError(std::string("zip: cannot extract entry '") + stat.m_filename + "'");
        }
        out[stat.m_filename] = std::move(data);
    }
    return out;
}

}  // namespace sr::data
