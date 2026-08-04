package net.osmand.util;

public class ArabicNormalizer {

    private static final String[] DIACRITIC_REGEX = {"[\\u064B-\\u065F]", "[\\u0610-\\u061A]", "[\\u06D6-\\u06ED]", "\\u0640", "\\u0670"};
    private static final String[] DIACRITIC_REPLACE = {
            "\u0624", "\u0648", // Replace Waw Hamza Above by Waw
            "\u0629", "\u0647", // Replace Ta Marbuta by Ha
            "\u064A", "\u0649", // Replace Ya by Alif Maksura
            "\u0626", "\u0649", // Replace Ya Hamza Above by Alif Maksura
            "\u0622", "\u0627", // Replace Alifs with Hamza Above
            "\u0623", "\u0627", // Replace Alifs with Hamza Below
            "\u0625", "\u0627"  // Replace with Madda Above by Alif
    };
    private static final String ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩";
    private static final String DIGITS_REPLACEMENT = "0123456789";

    public static boolean isSpecialArabic(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Scan the whole string, not just charAt(0). A query that merely STARTS with something
        // outside the Arabic block is still Arabic: "26 يوليو" - 26th of July St, one of Cairo's
        // main roads - begins with an ASCII digit, so the old first-character gate returned false
        // and hamza / ta-marbuta / alif-maqsura folding never ran for it. The failure was silent
        // and partial, because diacritic stripping still happened by another path, which is why it
        // read as "search is randomly unreliable" rather than as a bug.
        for (char c : text.toCharArray()) {
            if (isDiacritic(c) || isArabicDigit(c) || isNeedReplace(c)) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (int i = 0; i < DIACRITIC_REGEX.length; i++) {
            result = result.replaceAll(DIACRITIC_REGEX[i], "");
        }
        for (int i = 0; i < DIACRITIC_REPLACE.length; i = i + 2) {
            result = result.replace(DIACRITIC_REPLACE[i], DIACRITIC_REPLACE[i + 1]);
        }
        return replaceDigits(result);
    }

    private static String replaceDigits(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Same first-character gate as isSpecialArabic had, same fix. Arabic-Indic digits are
        // themselves in the ARABIC block, so "٢٦ يوليو" used to work while "26 يوليو" did not.
        if (!containsArabicDigit(text)) {
            return text;
        }

        char[] textChars = text.toCharArray();
        for (int i = 0; i < ARABIC_DIGITS.length(); i++) {
            char c = ARABIC_DIGITS.charAt(i);
            char replacement = DIGITS_REPLACEMENT.charAt(i);
            int index = text.indexOf(c);
            while (index >= 0) {
                textChars[index] = replacement;
                index = text.indexOf(c, index + 1);
            }
        }
        return String.valueOf(textChars);
    }

    private static boolean isDiacritic(char c) {
        return (c >= '\u064B' && c <= '\u065F') || 
                (c >= '\u0610' && c <= '\u061A') ||
                (c >= '\u06D6' && c <= '\u06ED') ||
                c == '\u0640' || c == '\u0670';
    }

    private static boolean isNeedReplace(char c) {
        String charAsString = String.valueOf(c);
        for (int i = 0; i < DIACRITIC_REPLACE.length; i += 2) {
            if (DIACRITIC_REPLACE[i].equals(charAsString)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsArabicDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isArabicDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArabicDigit(char c) {
        return c >= '\u0660' && c <= '\u0669';  // Arabic-Indic digits ٠-٩
    }
}
