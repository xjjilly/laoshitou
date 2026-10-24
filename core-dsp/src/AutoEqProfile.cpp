// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/AutoEqProfile.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <fstream>
#include <initializer_list>
#include <sstream>
#include <string>
#include <vector>

#include "dsp/AutoEqFitter.h"

namespace dsp {
namespace {

std::string Trim(const std::string& text) {
  const auto first = text.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return {};
  }
  const auto last = text.find_last_not_of(" \t\r\n");
  return text.substr(first, last - first + 1);
}

std::string NormalizeToken(const std::string& text) {
  std::string result;
  result.reserve(text.size());
  for (const char character : text) {
    const auto value = static_cast<unsigned char>(character);
    if (std::isalnum(value) != 0) {
      result.push_back(static_cast<char>(std::tolower(value)));
    }
  }
  return result;
}

bool MatchesOneOf(const std::string& value, std::initializer_list<const char*> candidates) {
  for (const char* candidate : candidates) {
    if (value == candidate) {
      return true;
    }
  }
  return false;
}

bool ParseBandType(const std::string& token, PeqFilterType* type) {
  const std::string normalized = NormalizeToken(token);
  if (MatchesOneOf(normalized, {"pk", "peak", "peaking", "bell"})) {
    *type = PeqFilterType::kPeaking;
    return true;
  }
  if (MatchesOneOf(normalized, {"lsc", "ls", "lowshelf", "lowshelving", "lsh"})) {
    *type = PeqFilterType::kLowShelf;
    return true;
  }
  if (MatchesOneOf(normalized, {"hsc", "hs", "highshelf", "highshelving", "hsh"})) {
    *type = PeqFilterType::kHighShelf;
    return true;
  }
  if (MatchesOneOf(normalized, {"hpf", "hp", "highpass", "highpassfilter"})) {
    *type = PeqFilterType::kHighPass;
    return true;
  }
  if (MatchesOneOf(normalized, {"lpf", "lp", "lowpass", "lowpassfilter"})) {
    *type = PeqFilterType::kLowPass;
    return true;
  }
  return false;
}

bool ParseNumber(const std::string& token, double* value) {
  const std::string trimmed = Trim(token);
  if (trimmed.empty()) {
    return false;
  }
  const char* begin = trimmed.c_str();
  char* end = nullptr;
  const double parsed = std::strtod(begin, &end);
  if (end == begin || !std::isfinite(parsed)) {
    return false;
  }
  *value = parsed;
  return true;
}

bool ParseEnabled(const std::string& token, bool* enabled) {
  const std::string normalized = NormalizeToken(token);
  if (normalized.empty()) {
    return false;
  }
  if (MatchesOneOf(normalized, {"on", "true", "1", "yes", "enabled", "active"})) {
    *enabled = true;
    return true;
  }
  if (MatchesOneOf(normalized, {"off", "false", "0", "no", "disabled", "inactive"})) {
    *enabled = false;
    return true;
  }
  return false;
}

bool ParsePreampLine(const std::string& line, double* preamp_db) {
  if (line.rfind("Preamp:", 0) != 0) {
    return false;
  }
  std::istringstream rest(line.substr(7));
  return static_cast<bool>(rest >> *preamp_db);
}

bool HasFilterLine(const std::string& text) {
  std::istringstream input(text);
  std::string line;
  while (std::getline(input, line)) {
    const std::string trimmed = Trim(line);
    if (!trimmed.empty() && trimmed[0] != '#' && trimmed.rfind("Filter", 0) == 0 &&
        trimmed.find(':') != std::string::npos) {
      return true;
    }
  }
  return false;
}

std::vector<std::string> SplitDelimited(const std::string& line, char delimiter) {
  std::vector<std::string> fields;
  std::string field;
  bool quoted = false;
  for (const char character : line) {
    if (character == '"') {
      quoted = !quoted;
      continue;
    }
    if (character == delimiter && !quoted) {
      fields.push_back(Trim(field));
      field.clear();
    } else {
      field.push_back(character);
    }
  }
  fields.push_back(Trim(field));
  return fields;
}

int FindColumn(const std::vector<std::string>& header,
               std::initializer_list<const char*> candidates) {
  for (const char* candidate : candidates) {
    for (std::size_t i = 0; i < header.size(); ++i) {
      if (header[i] == candidate) {
        return static_cast<int>(i);
      }
    }
  }
  return -1;
}

bool LooksLikeDataRow(const std::vector<std::string>& fields) {
  bool all_numeric = !fields.empty();
  for (const std::string& field : fields) {
    double value = 0.0;
    if (!ParseNumber(field, &value)) {
      all_numeric = false;
      break;
    }
  }
  if (all_numeric) {
    return true;
  }
  PeqFilterType type = PeqFilterType::kPeaking;
  return !fields.empty() && ParseBandType(fields.front(), &type);
}

std::string Cell(const std::vector<std::string>& fields, int index) {
  if (index < 0 || static_cast<std::size_t>(index) >= fields.size()) {
    return {};
  }
  return fields[static_cast<std::size_t>(index)];
}

bool ParseParametricText(const std::string& text, AutoEqProfile* profile, std::string* error) {
  std::istringstream input(text);
  std::string line;
  int line_number = 0;

  while (std::getline(input, line)) {
    ++line_number;
    line = Trim(line);
    if (line.empty() || line[0] == '#') {
      continue;
    }

    if (line.rfind("Preamp:", 0) == 0) {
      if (!ParsePreampLine(line, &profile->preamp_db)) {
        if (error != nullptr) {
          *error = "第 " + std::to_string(line_number) + " 行 Preamp 解析失败";
        }
        return false;
      }
      continue;
    }

    if (line.rfind("Filter", 0) != 0) {
      continue;
    }

    const auto colon = line.find(':');
    if (colon == std::string::npos) {
      if (error != nullptr) {
        *error = "第 " + std::to_string(line_number) + " 行缺少冒号";
      }
      return false;
    }

    std::istringstream rest(line.substr(colon + 1));
    std::string state;
    std::string type_token;
    if (!(rest >> state >> type_token)) {
      if (error != nullptr) {
        *error = "第 " + std::to_string(line_number) + " 行 Filter 解析失败";
      }
      return false;
    }

    PeqBandConfig band;
    band.enabled = (NormalizeToken(state) == "on");
    if (!ParseBandType(type_token, &band.type)) {
      if (error != nullptr) {
        *error = "第 " + std::to_string(line_number) + " 行不支持的类型：" + type_token;
      }
      return false;
    }

    bool have_fc = false;
    bool have_gain = false;
    bool have_q = false;
    bool have_slope = false;
    bool bad_order = false;
    std::string token;
    while (rest >> token) {
      if (token == "Fc") {
        if (!(rest >> band.frequency)) {
          break;
        }
        have_fc = true;
      } else if (token == "Gain") {
        if (!(rest >> band.gain_db)) {
          break;
        }
        have_gain = true;
      } else if (token == "Q") {
        if (!(rest >> band.q)) {
          break;
        }
        have_q = true;
      } else if (token == "Slope") {
        if (!(rest >> band.slope)) {
          break;
        }
        band.use_slope = true;
        have_slope = true;
      } else if (token == "Order" || token == "Cascade") {
        int order = 0;
        if (!(rest >> order) || (order != 1 && order != 2)) {
          bad_order = true;
          break;
        }
        band.order = order;
      }
    }

    if (bad_order) {
      if (error != nullptr) {
        *error = "第 " + std::to_string(line_number) + " 行 Order 必须为 1 或 2";
      }
      return false;
    }

    const bool is_shelf =
        band.type == PeqFilterType::kLowShelf || band.type == PeqFilterType::kHighShelf;
    const bool is_hp_lp =
        band.type == PeqFilterType::kHighPass || band.type == PeqFilterType::kLowPass;
    const bool missing_q = is_shelf ? (!have_q && !have_slope) : !have_q;
    if (!have_fc || (!have_gain && !is_hp_lp) || missing_q) {
      if (error != nullptr) {
        *error = "第 " + std::to_string(line_number) + " 行缺少 Fc/Gain/Q（或斜率）";
      }
      return false;
    }

    profile->bands.push_back(band);
  }

  return true;
}

bool ParseGraphicEq(const std::string& text, AutoEqProfile* profile, std::string* error,
                    const AutoEqOptions& options) {
  const auto colon = text.find(':');
  if (colon == std::string::npos) {
    if (error != nullptr) {
      *error = "GraphicEQ 缺少冒号";
    }
    return false;
  }

  std::string body = text.substr(colon + 1);
  for (char& character : body) {
    if (character == ';' || character == ',' || character == '\r' || character == '\n' ||
        character == '\t') {
      character = ' ';
    }
  }

  std::istringstream stream(body);
  std::vector<FrPoint> points;
  double frequency = 0.0;
  double gain = 0.0;
  while (stream >> frequency >> gain) {
    points.push_back(FrPoint{frequency, gain});
  }

  if (points.size() < 4) {
    if (error != nullptr) {
      *error = "GraphicEQ 数据点太少";
    }
    return false;
  }
  return FitPeqToCurve(points, profile, error, options);
}

bool ParseCsv(const std::string& text, AutoEqProfile* profile, std::string* error,
              const AutoEqOptions& options) {
  std::vector<std::vector<std::string>> rows;
  double file_preamp = 0.0;
  bool have_file_preamp = false;
  char delimiter = ',';

  std::istringstream input(text);
  std::string line;
  int line_number = 0;
  while (std::getline(input, line)) {
    ++line_number;
    line = Trim(line);
    if (line.empty() || line[0] == '#') {
      continue;
    }
    if (line.rfind("Preamp:", 0) == 0) {
      if (!ParsePreampLine(line, &file_preamp)) {
        if (error != nullptr) {
          *error = "第 " + std::to_string(line_number) + " 行 Preamp 解析失败";
        }
        return false;
      }
      have_file_preamp = true;
      continue;
    }
    if (rows.empty() && line.find(',') == std::string::npos &&
        line.find(';') != std::string::npos) {
      delimiter = ';';
    }
    rows.push_back(SplitDelimited(line, delimiter));
  }

  if (rows.empty()) {
    if (error != nullptr) {
      *error = "EQ 文件为空或格式不受支持";
    }
    return false;
  }

  const bool has_header = !LooksLikeDataRow(rows.front());
  std::vector<std::string> header;
  std::size_t first_row = 0;
  if (has_header) {
    header.reserve(rows.front().size());
    for (const std::string& field : rows.front()) {
      header.push_back(NormalizeToken(field));
    }
    first_row = 1;
  }

  int type_index = -1;
  int frequency_index = -1;
  int gain_index = -1;
  int q_index = -1;
  int slope_index = -1;
  int order_index = -1;
  int enabled_index = -1;
  int correction_index = -1;
  int error_index = -1;
  int preamp_index = -1;

  if (has_header) {
    type_index = FindColumn(header, {"type", "filter", "filtertype", "ftype", "bandtype", "kind"});
    frequency_index = FindColumn(header, {"frequency", "freq", "fc", "hz", "frequencyhz", "fchz"});
    gain_index = FindColumn(header, {"gain", "gaindb", "db", "level", "amplitude"});
    q_index = FindColumn(header, {"q", "quality", "qfactor", "qvalue"});
    slope_index = FindColumn(header, {"slope", "s"});
    order_index = FindColumn(header, {"order", "cascade"});
    enabled_index = FindColumn(header, {"enabled", "enable", "active", "state", "on"});
    correction_index =
        FindColumn(header, {"equalization", "equalisation", "equalizationdb", "correction",
                            "correctiondb", "eq", "gain", "gaindb", "db", "level"});
    error_index = FindColumn(header, {"error", "errordb"});
    preamp_index = FindColumn(header, {"preamp", "preampdb"});
  } else {
    PeqFilterType type = PeqFilterType::kPeaking;
    if (rows.front().size() >= 3 && ParseBandType(rows.front().front(), &type)) {
      type_index = 0;
      frequency_index = 1;
      gain_index = 2;
      q_index = 3;
      enabled_index = 4;
    } else {
      frequency_index = 0;
      correction_index = 1;
    }
  }

  if (type_index >= 0) {
    bool have_preamp = have_file_preamp;
    for (std::size_t i = first_row; i < rows.size(); ++i) {
      const std::vector<std::string>& fields = rows[i];
      if (fields.empty()) {
        continue;
      }

      PeqBandConfig band;
      if (!ParseBandType(Cell(fields, type_index), &band.type)) {
        if (error != nullptr) {
          *error = "CSV 第 " + std::to_string(i + 1) + " 行滤波器类型无效";
        }
        return false;
      }
      if (!ParseNumber(Cell(fields, frequency_index), &band.frequency) ||
          !ParseNumber(Cell(fields, gain_index), &band.gain_db)) {
        if (error != nullptr) {
          *error = "CSV 第 " + std::to_string(i + 1) + " 行缺少 Fc/Gain";
        }
        return false;
      }

      band.q = band.type == PeqFilterType::kPeaking ? 1.0 : 0.707;
      if (q_index >= 0 && !Cell(fields, q_index).empty()) {
        if (!ParseNumber(Cell(fields, q_index), &band.q) || band.q <= 0.0) {
          if (error != nullptr) {
            *error = "CSV 第 " + std::to_string(i + 1) + " 行 Q 无效";
          }
          return false;
        }
      }
      if (slope_index >= 0 && !Cell(fields, slope_index).empty()) {
        if (!ParseNumber(Cell(fields, slope_index), &band.slope) || band.slope <= 0.0) {
          if (error != nullptr) {
            *error = "CSV 第 " + std::to_string(i + 1) + " 行 Slope 无效";
          }
          return false;
        }
        band.use_slope =
            band.type == PeqFilterType::kLowShelf || band.type == PeqFilterType::kHighShelf;
      }
      if (order_index >= 0 && !Cell(fields, order_index).empty()) {
        double order = 0.0;
        if (!ParseNumber(Cell(fields, order_index), &order) || (order != 1.0 && order != 2.0)) {
          if (error != nullptr) {
            *error = "CSV 第 " + std::to_string(i + 1) + " 行 Order 必须为 1 或 2";
          }
          return false;
        }
        band.order = static_cast<int>(order);
      }

      band.enabled = true;
      if (enabled_index >= 0 && !Cell(fields, enabled_index).empty() &&
          !ParseEnabled(Cell(fields, enabled_index), &band.enabled)) {
        if (error != nullptr) {
          *error = "CSV 第 " + std::to_string(i + 1) + " 行启用状态无效";
        }
        return false;
      }

      if (preamp_index >= 0 && !have_preamp && !Cell(fields, preamp_index).empty()) {
        if (!ParseNumber(Cell(fields, preamp_index), &file_preamp)) {
          if (error != nullptr) {
            *error = "CSV 第 " + std::to_string(i + 1) + " 行 Preamp 无效";
          }
          return false;
        }
        have_preamp = true;
      }

      profile->bands.push_back(band);
    }

    if (profile->bands.empty()) {
      if (error != nullptr) {
        *error = "CSV 中没有可用的 EQ 频段";
      }
      return false;
    }
    profile->preamp_db = have_file_preamp ? file_preamp : 0.0;
    return true;
  }

  if (frequency_index < 0 || (correction_index < 0 && error_index < 0)) {
    if (error != nullptr) {
      *error = "CSV 缺少频率列，或缺少 equalization/error/gain 校正列";
    }
    return false;
  }

  const bool negate_error = correction_index < 0 && error_index >= 0;
  const int value_index = correction_index >= 0 ? correction_index : error_index;

  std::vector<FrPoint> points;
  for (std::size_t i = first_row; i < rows.size(); ++i) {
    const std::vector<std::string>& fields = rows[i];
    double frequency = 0.0;
    double gain = 0.0;
    if (!ParseNumber(Cell(fields, frequency_index), &frequency) ||
        !ParseNumber(Cell(fields, value_index), &gain)) {
      continue;
    }
    points.push_back(FrPoint{frequency, negate_error ? -gain : gain});
  }

  if (points.size() < 4) {
    if (error != nullptr) {
      *error = "CSV 中的频响数据点太少";
    }
    return false;
  }
  return FitPeqToCurve(points, profile, error, options);
}

void ApplyOptions(const AutoEqOptions& options, AutoEqProfile* profile) {
  if (profile == nullptr) {
    return;
  }
  if (options.order > 1) {
    const int order = std::clamp(options.order, 1, 2);
    for (PeqBandConfig& band : profile->bands) {
      band.order = order;
    }
  }
  if (options.use_slope) {
    const double slope = std::clamp(options.slope, 0.05, 1.0);
    for (PeqBandConfig& band : profile->bands) {
      if (band.type != PeqFilterType::kPeaking) {
        band.use_slope = true;
        band.slope = slope;
      }
    }
  }
}

}  // namespace

bool ParseAutoEqProfile(const std::string& text, AutoEqProfile* profile, std::string* error,
                        const AutoEqOptions& options) {
  if (profile == nullptr) {
    return false;
  }

  *profile = AutoEqProfile{};
  std::string content = text;
  if (content.rfind("\xEF\xBB\xBF", 0) == 0) {
    content.erase(0, 3);
  }

  const std::string trimmed = Trim(content);
  if (trimmed.empty()) {
    if (error != nullptr) {
      *error = "EQ 文件为空";
    }
    return false;
  }

  bool parsed = false;
  if (trimmed.rfind("GraphicEQ:", 0) == 0) {
    parsed = ParseGraphicEq(trimmed, profile, error, options);
  } else if (HasFilterLine(content)) {
    parsed = ParseParametricText(content, profile, error);
  } else {
    parsed = ParseCsv(content, profile, error, options);
  }
  if (parsed) {
    ApplyOptions(options, profile);
  }
  return parsed;
}

bool LoadAutoEqProfile(const std::string& path, AutoEqProfile* profile, std::string* error,
                       const AutoEqOptions& options) {
  std::ifstream file(path);
  if (!file) {
    if (error != nullptr) {
      *error = "无法打开 EQ 配置文件";
    }
    return false;
  }

  std::ostringstream content;
  content << file.rdbuf();
  return ParseAutoEqProfile(content.str(), profile, error, options);
}

}  // namespace dsp
