import Foundation

/// Number and symbol pages plus the nine-key punctuation strip. Android keeps the same
/// table in `PunctuationLayout.java`; both are checked against
/// `Tests/RimesCoreTests/Fixtures/punctuation-layout.tsv`.
public enum PunctuationLayout {
    public static let numeric = ["1234567890", "-/:;()$&@\"", ".,?!'"]
    public static let symbols = ["[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'"]
    /// Chinese input shows the marks it types. The half-width `.` stays for decimals.
    public static let chineseNumeric = ["1234567890", "-/：；（）￥@“”", "。，、？！."]
    public static let chineseSymbols = ["【】｛｝#%^*+=", "_—\\｜～《》$&·", "…‘’「」〈〉"]
    /// Offered in the candidate row by the nine-key punctuation key.
    public static let strip = ["，", "。", "？", "！", "、", "：", "；", "…", "“", "”", "（", "）"]
    private static let sentenceMarks: Set<String> = ["，", "。", "？", "！", "、", "：", "；"]

    public static func rows(symbols page: Bool, chinese: Bool) -> [String] {
        page ? (chinese ? chineseSymbols : symbols) : (chinese ? chineseNumeric : numeric)
    }
    /// A sentence mark typed first on a number page was the reason for opening it.
    public static func returnsToLetters(after text: String) -> Bool { sentenceMarks.contains(text) }
}
