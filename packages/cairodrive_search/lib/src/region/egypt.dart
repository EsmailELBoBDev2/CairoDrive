import '../model/search_result.dart';

/// Cairo/Egypt-aware helpers.
///
/// These exist because CairoDrive's users type a mix of Egyptian Arabic,
/// Modern Standard Arabic and transliterated Latin, and expect greater-Cairo
/// places to surface first without exact Google relevance being discarded.
class EgyptRegion {
  const EgyptRegion._();

  /// ISO-3166-1 alpha-2 for Egypt, used as the Places `regionCode`.
  static const String regionCode = 'EG';

  /// Fallback bias centre when the device has no fix yet: central Cairo
  /// (Tahrir Square).
  static const LatLng cairoCenter = LatLng(30.0444, 31.2357);

  /// Radius covering greater Cairo — Giza, New Cairo, 6th of October,
  /// Sheikh Zayed, Shubra el-Kheima, Helwan.
  static const double greaterCairoRadiusMeters = 60000;

  /// Rough bounding box of the greater-Cairo metropolitan area.
  static const double _minLat = 29.75;
  static const double _maxLat = 30.35;
  static const double _minLng = 30.75;
  static const double _maxLng = 31.75;

  /// True when [p] falls inside greater Cairo.
  static bool isInGreaterCairo(LatLng p) =>
      p.latitude >= _minLat &&
      p.latitude <= _maxLat &&
      p.longitude >= _minLng &&
      p.longitude <= _maxLng;

  /// Arabic script range check. Covers Arabic (0600–06FF), Arabic Supplement
  /// (0750–077F) and Arabic Presentation Forms (FB50–FDFF, FE70–FEFF).
  static bool isArabic(int codeUnit) =>
      (codeUnit >= 0x0600 && codeUnit <= 0x06FF) ||
      (codeUnit >= 0x0750 && codeUnit <= 0x077F) ||
      (codeUnit >= 0xFB50 && codeUnit <= 0xFDFF) ||
      (codeUnit >= 0xFE70 && codeUnit <= 0xFEFF);

  /// Infer the Places `languageCode` from the query's script.
  ///
  /// A query with any Arabic character is treated as Arabic — Egyptian users
  /// routinely mix a Latin brand name into an Arabic phrase, and Arabic results
  /// are the better answer in that case.
  static String inferLanguageCode(String query) {
    for (final unit in query.runes) {
      if (isArabic(unit)) return 'ar';
    }
    return 'en';
  }

  /// Normalise Arabic text for comparison: strip tatweel and harakat, and fold
  /// the alef/ya/ta-marbuta variants Egyptian users type interchangeably.
  static String normalizeArabic(String input) {
    final buffer = StringBuffer();
    for (final rune in input.runes) {
      // Skip harakat (064B–0652), superscript alef (0670) and tatweel (0640).
      if ((rune >= 0x064B && rune <= 0x0652) ||
          rune == 0x0670 ||
          rune == 0x0640) {
        continue;
      }
      final folded = switch (rune) {
        0x0623 || 0x0625 || 0x0622 || 0x0671 => 0x0627, // أإآٱ → ا
        0x0649 => 0x064A, // ى → ي
        0x0629 => 0x0647, // ة → ه
        _ => rune,
      };
      buffer.writeCharCode(folded);
    }
    return buffer.toString();
  }
}
