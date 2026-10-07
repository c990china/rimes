import XCTest
@testable import RimesCore

final class PunctuationLayoutTests: XCTestCase {
    private func fixture() throws -> [String: [String]] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "punctuation-layout", withExtension: "tsv", subdirectory: "Fixtures"))
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        return Dictionary(uniqueKeysWithValues: lines.map { line in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            return (fields[0], Array(fields.dropFirst()))
        })
    }
    func testTableMatchesTheFixtureSharedWithAndroid() throws {
        let shared = try fixture()
        XCTAssertEqual(shared.count, 6)
        XCTAssertEqual(PunctuationLayout.rows(symbols: false, chinese: false), shared["numeric"])
        XCTAssertEqual(PunctuationLayout.rows(symbols: true, chinese: false), shared["symbols"])
        XCTAssertEqual(PunctuationLayout.rows(symbols: false, chinese: true), shared["numeric.zh"])
        XCTAssertEqual(PunctuationLayout.rows(symbols: true, chinese: true), shared["symbols.zh"])
        XCTAssertEqual([PunctuationLayout.strip.joined()], shared["strip"])
        let sentence = try XCTUnwrap(shared["sentence"]?.first).map(String.init)
        XCTAssertEqual(PunctuationLayout.strip.filter(PunctuationLayout.returnsToLetters(after:)), sentence)
    }
    func testEveryPageHasDistinctSingleKeysThatFitTheLetterRows() {
        for symbols in [false, true] {
            for chinese in [false, true] {
                let rows = PunctuationLayout.rows(symbols: symbols, chinese: chinese)
                XCTAssertEqual(rows.count, 3)
                XCTAssertEqual(rows[0].count, 10); XCTAssertEqual(rows[1].count, 10)
                // The third row sits between two side keys, like Z–M.
                XCTAssertLessThanOrEqual(rows[2].count, 7)
                let keys = rows.joined()
                XCTAssertEqual(Set(keys).count, keys.count)
                XCTAssertTrue(keys.allSatisfy { $0.utf16.count == 1 })
            }
        }
    }
    func testOnlySentenceMarksReturnToLetters() {
        for mark in ["，", "。", "？", "！", "、", "：", "；"] { XCTAssertTrue(PunctuationLayout.returnsToLetters(after: mark)) }
        for other in ["1", ".", ",", "“", "（", "…", "-", ""] { XCTAssertFalse(PunctuationLayout.returnsToLetters(after: other)) }
    }
}
