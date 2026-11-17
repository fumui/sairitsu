#include <string>

#include "data/gzip.h"
#include "data/load_error.h"
#include "data/png.h"
#include "data/zip.h"
#include "doctest.h"
#include "support/archive.h"

using namespace sr::data;

TEST_SUITE("data") {
TEST_CASE("gunzip roundtrip") {
    std::string payload;
    for (int i = 0; i < 1000; ++i) payload += "hello sonolus " + std::to_string(i) + "\n";
    const auto gz = sr::test::make_gzip(payload);
    const auto out = gunzip(gz);
    CHECK(std::string(out.begin(), out.end()) == payload);
}

TEST_CASE("gunzip of an empty payload succeeds") {
    const auto out = gunzip(sr::test::make_gzip(""));
    CHECK(out.empty());
}

TEST_CASE("gunzip enforces the output cap") {
    const auto gz = sr::test::make_gzip(std::string(100000, 'a'));
    CHECK(gunzip(gz, 100000).size() == 100000);
    CHECK_THROWS_AS(gunzip(gz, 99999), LoadError);
    CHECK_THROWS_AS(gunzip(gz, 1000), LoadError);
}

TEST_CASE("unzip enforces the entry cap") {
    const auto zip = sr::test::make_zip({{"big", std::string(5000, 'b')}});
    CHECK(unzip(zip, 5000).size() == 1);
    CHECK_THROWS_AS(unzip(zip, 4999), LoadError);
}

TEST_CASE("unzip rejects a corrupt or truncated archive") {
    auto zip = sr::test::make_zip({{"0", std::string(3000, 'x')}, {"1", "y"}});
    auto truncated = zip;
    truncated.resize(zip.size() / 2);
    CHECK_THROWS_AS(unzip(truncated), LoadError);
    auto corrupt = zip;
    corrupt[40] ^= 0xff;  // inside the first entry's data
    CHECK_THROWS_AS(unzip(corrupt), LoadError);
}

TEST_CASE("truncated png throws LoadError") {
    std::vector<uint8_t> rgba(16 * 16 * 4, 77);
    auto png = sr::test::make_png(16, 16, rgba);
    png.resize(png.size() / 2);
    CHECK_THROWS_AS(decode_png(png), LoadError);
}

TEST_CASE("truncated gzip throws LoadError") {
    auto gz = sr::test::make_gzip(std::string(5000, 'x') + "tail");
    gz.resize(gz.size() / 2);
    CHECK_THROWS_AS(gunzip(gz), LoadError);
    CHECK_THROWS_AS(gunzip(std::vector<uint8_t>{1, 2, 3}), LoadError);
}

TEST_CASE("gzip with a corrupt checksum throws LoadError") {
    auto gz = sr::test::make_gzip("some payload");
    gz[gz.size() - 8] ^= 0xff;
    CHECK_THROWS_AS(gunzip(gz), LoadError);
}

TEST_CASE("unzip lists entries") {
    const auto zip = sr::test::make_zip({{"0", "first clip"}, {"1", "second clip"}});
    const auto files = unzip(zip);
    REQUIRE(files.size() == 2);
    CHECK(std::string(files.at("0").begin(), files.at("0").end()) == "first clip");
    CHECK(std::string(files.at("1").begin(), files.at("1").end()) == "second clip");
    CHECK_THROWS_AS(unzip(std::vector<uint8_t>{1, 2, 3, 4}), LoadError);
}

TEST_CASE("png decodes 2x2 rgba") {
    const std::vector<uint8_t> rgba = {255, 0, 0, 255, 0,  255, 0,  128,
                                       0,   0, 255, 0, 10, 20,  30, 40};
    const Image image = decode_png(sr::test::make_png(2, 2, rgba));
    CHECK(image.width == 2);
    CHECK(image.height == 2);
    CHECK(image.rgba == rgba);
    CHECK_THROWS_AS(decode_png(std::vector<uint8_t>{1, 2, 3}), LoadError);
}
}
