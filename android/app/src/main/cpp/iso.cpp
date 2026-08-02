
#include "iso.hpp"
#include "Utilities/File.h"
#include "util/types.hpp"
#include <bit>
#include <cctype>
#include <codecvt>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <memory>
#include <span>
#include <string>

namespace {
class iso_virtual_dir final : public fs::dir_base {
public:
  explicit iso_virtual_dir(std::vector<fs::dir_entry> entries)
      : m_entries(std::move(entries)) {}

  bool read(fs::dir_entry &entry) override {
    if (m_index >= m_entries.size()) {
      return false;
    }
    entry = m_entries[m_index++];
    return true;
  }

  void rewind() override { m_index = 0; }

private:
  std::vector<fs::dir_entry> m_entries;
  std::size_t m_index = 0;
};
} // namespace

static std::string u16_ne_to_string(const char16_t *bytes, std::size_t count) {
  std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> convert;
  return convert.to_bytes(bytes, bytes + count);
}

static std::string u16_se_to_string(const char16_t *bytes, std::size_t count) {
  auto seBytes = (se_t<char16_t, true, alignof(char16_t)> *)bytes;

  std::vector<char16_t> neBytes(count);
  for (std::size_t i = 0; i < count; ++i) {
    neBytes[i] = seBytes[i];
  }

  return u16_ne_to_string(neBytes.data(), neBytes.size());
}

static std::string u16_be_to_string(const char16_t *bytes, std::size_t count) {
  if constexpr (std::endian::native == std::endian::big) {
    return u16_ne_to_string(bytes, count);
  } else {
    return u16_se_to_string(bytes, count);
  }
}

static std::string decodeString(std::string_view data,
                                iso::StringEncoding encoding) {
  switch (encoding) {
  case iso::StringEncoding::ascii:
    break;

  case iso::StringEncoding::utf16_be:
    return u16_be_to_string((char16_t *)data.data(), data.size() / 2);
  }

  return std::string(data);
}

bool iso_fs::initialize() {
  constexpr std::size_t primaryVolumeDescOffset = 16;
  ensure(m_dev->block_size() >= sizeof(iso::VolumeHeader));
  std::vector<std::byte> block(m_dev->block_size());

  std::optional<iso::PrimaryVolumeDescriptor> primaryVolume;
  std::optional<iso::PrimaryVolumeDescriptor> supplementaryVolume;

  for (std::size_t i = 0; i < 256; ++i) {
    if (m_dev->read(primaryVolumeDescOffset + i, block.data(), 1) != 1) {
      break;
    }

    auto header = reinterpret_cast<iso::VolumeHeader *>(block.data());

    if (header->type == 255) {
      break;
    }

    if (std::memcmp(header->standard_id, "CD001", 5) != 0) {
      continue;
    }

    if (header->type == 1) {
      primaryVolume =
          *reinterpret_cast<iso::PrimaryVolumeDescriptor *>(block.data());
      continue;
    }

    if (header->type == 2) {
      std::printf("found supplementary volume\n");
      supplementaryVolume =
          *reinterpret_cast<iso::PrimaryVolumeDescriptor *>(block.data());
      continue;
    }
  }

  if (!primaryVolume) {
    return false;
  }

  auto &pvd = supplementaryVolume ? *supplementaryVolume : *primaryVolume;
  m_encoding = supplementaryVolume ? iso::StringEncoding::utf16_be
                                   : iso::StringEncoding::ascii;
  m_root_dir = pvd.root;

  return true;
}

fs::file iso_fs::open(const std::filesystem::path &path, fs::open_mode mode) {
  if (mode != fs::open_mode::read) {
    return {};
  }

  auto optEntry = open_entry(path, false);
  if (!optEntry) {
    return {};
  }

  return read_file(*optEntry);
}

fs::dir iso_fs::open_dir(const std::filesystem::path &path) {
  auto optEntry = open_entry(path, true);
  if (!optEntry) {
    return {};
  }

  auto items = read_dir(*optEntry);
  std::vector<fs::dir_entry> result_items(items.first.size());

  for (std::size_t i = 0; i < result_items.size(); ++i) {
    result_items[i] = items.first[i].to_fs_entry(items.second[i]);
  }

  fs::dir result;
  result.reset(std::make_unique<iso_virtual_dir>(std::move(result_items)));
  return result;
}

std::optional<iso::DirEntry>
iso_fs::open_entry(const std::filesystem::path &path, bool isDir) {
  auto pathString = std::filesystem::weakly_canonical(path).string();

  if (pathString == "/" || pathString == "\\" || pathString.empty()) {
    return m_root_dir;
  }

  auto item = m_root_dir;
  auto pathView = std::string_view(pathString);

  auto isStringEqNoCase = [](std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
      return false;
    }

    for (std::size_t i = 0; i < lhs.size(); ++i) {
      if (std::tolower(lhs[i]) != std::tolower(rhs[i])) {
        return false;
      }
    }

    return true;
  };

  while (!pathView.empty()) {
    auto sepPos = pathView.find_first_of("/\\");
    if (sepPos == 0) {
      pathView.remove_prefix(1);

      if (pathView.empty() && !isDir) {
        return {};
      }

      continue;
    }

    if ((item.flags & iso::DirEntryFlags::Directory) !=
        iso::DirEntryFlags::Directory) {
      return {};
    }

    auto dirName = pathView.substr(0, sepPos);
    if (sepPos == std::string_view::npos) {
      pathView = {};
    } else {
      pathView.remove_prefix(sepPos + (isDir ? 1 : 0));
    }

    auto items = read_dir(item);

    bool found = false;
    for (std::size_t i = 0; i < items.first.size(); ++i) {
      if (!isStringEqNoCase(items.second[i], dirName)) {
        continue;
      }

      item = items.first[i];
      found = true;
      break;
    }

    if (!found) {
      return {};
    }
  }

  if (isDir) {
    if ((item.flags & iso::DirEntryFlags::Directory) !=
        iso::DirEntryFlags::Directory) {
      return {};
    }
  } else {
    if ((item.flags & iso::DirEntryFlags::Directory) ==
        iso::DirEntryFlags::Directory) {
      return {};
    }
  }

  return item;
}

std::pair<std::vector<iso::DirEntry>, std::vector<std::string>>
iso_fs::read_dir(const iso::DirEntry &entry) {
  if ((entry.flags & iso::DirEntryFlags::Directory) !=
      iso::DirEntryFlags::Directory) {
    return {};
  }

  auto block_size = m_dev->block_size();
  auto total_block_count = (entry.length.value() + block_size - 1) / block_size;
  auto total_buffer_block_count = std::min<std::size_t>(total_block_count, 10);

  std::vector<std::byte> buffer(total_buffer_block_count * block_size);
  auto first_block = entry.lba.value();

  std::vector<iso::DirEntry> isoEntries;
  std::vector<std::string> names;

  for (std::size_t block = first_block, end = first_block + total_block_count;
       block < end;) {
    auto block_count =
        m_dev->read(block, buffer.data(), total_buffer_block_count);
    block += block_count;

    std::size_t buffer_offset = 0;
    std::size_t buffer_size = block_count * block_size;

    std::size_t count = 0;
    while (buffer_offset < buffer_size) {
      auto item = reinterpret_cast<const iso::DirEntry *>(buffer.data() +
                                                          buffer_offset);
      if (item->entry_length == 0) {
        buffer_offset += block_size;
        buffer_offset &= ~(block_size - 1);
        continue;
      }

      auto filename_end =
          sizeof(iso::DirEntry) +
          ((item->filename_length + 1) & ~static_cast<std::size_t>(1));

      if (item->entry_length < filename_end) {
        buffer_offset += item->entry_length;
        continue;
      }

      auto susp_area = std::span(buffer.data() + buffer_offset + filename_end,
                                 item->entry_length - filename_end);
      struct [[gnu::packed]] SuspHeader {
        char signature[2];
        u8 length;
        u8 version;
      };

      if (susp_area.size() > sizeof(SuspHeader)) {
        auto susp_header = reinterpret_cast<SuspHeader *>(susp_area.data());

        // TODO: extensions support
      }

      buffer_offset += item->entry_length;

      if (item->filename_length == 0 ||
          item->filename_length + sizeof(iso::DirEntry) > item->entry_length) {
        continue;
      }

      auto filename =
          item->filename_length > 1
              ? decodeString({reinterpret_cast<const char *>(item + 1),
                              item->filename_length},
                             m_encoding)
              : std::string{};

      if (item->filename_length == 1) {
        char c = *reinterpret_cast<const char *>(item + 1);
        // can be special name
        if (c == 0) {
          filename = ".";
        } else if (c == 1) {
          filename = "..";
        }
      }

      filename = filename.substr(0, filename.find(';'));
      if (filename.empty()) {
        continue;
      }

      isoEntries.push_back(*item);
      names.emplace_back(filename);
    }
  }

  return {std::move(isoEntries), std::move(names)};
}

fs::file iso_fs::read_file(const iso::DirEntry &entry) {
  if ((entry.flags & iso::DirEntryFlags::Directory) ==
      iso::DirEntryFlags::Directory) {
    return {};
  }

  if (entry.length.value() == 0) {
    return fs::make_stream<std::vector<std::uint8_t>>();
  }

  auto block_size = m_dev->block_size();
  auto block_count = (entry.length.value() + block_size - 1) / block_size;

  std::vector<std::uint8_t> data;
  data.resize(block_count * block_size);
  if (!m_dev->read(entry.lba.value(), data.data(), block_count)) {
    return {};
  }

  data.resize(entry.length.value());
  return fs::make_stream(std::move(data), entry.to_fs_stat());
}
