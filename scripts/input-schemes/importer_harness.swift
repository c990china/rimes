import Foundation

// Host command around the unmodified iOS importer source. For each archive it prints
// one JSON line with the importer's own review; with --stage it also stages the
// recommended schemes (all importable ones when none is recommended) the way the
// app does before deployment. scheme_build.py verify compiles this next to a copy
// of platforms/ios/App/RimeSchemeImportService.swift.
struct ImportedRimeSchema: Codable, Identifiable, Hashable, Sendable { var id: String; var name: String }

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    print(String(decoding: data, as: UTF8.self))
}

let arguments = Array(CommandLine.arguments.dropFirst())
var stageRoot: URL? = nil
var archives = [String]()
var index = 0
while index < arguments.count {
    if arguments[index] == "--stage" { stageRoot = URL(fileURLWithPath: arguments[index + 1]); index += 2; continue }
    archives.append(arguments[index]); index += 1
}
for path in archives {
    let url = URL(fileURLWithPath: path)
    do {
        let review = try RimeSchemeImportService.inspect(url: url)
        var result: [String: Any] = [
            "archive": url.lastPathComponent, "fatal": NSNull(), "sha256": review.archiveSHA256,
            "fileCount": review.fileCount, "expandedBytes": review.expandedBytes,
            "warnings": review.warnings, "unsupported": review.unsupportedFeatures,
            "schemes": review.schemes.map { ["id": $0.id, "name": $0.name, "dependencies": $0.dependencies,
                "recommended": $0.recommended, "blocking": $0.blockingIssues, "warnings": $0.warnings] as [String: Any] },
        ]
        if let stageRoot {
            let usable = review.schemes.filter { $0.blockingIssues.isEmpty }
            let preferred = usable.filter(\.recommended)
            let selected = (preferred.isEmpty ? usable : preferred).map(\.id)
            if selected.isEmpty { emit(result); continue }
            do {
                let staged = try RimeSchemeImportService.stage(review: review, selectedSchemaIDs: selected, destinationRoot: stageRoot)
                result["staged"] = ["root": staged.rootURL.path, "selected": staged.schemaIDs, "deployment": staged.deploymentSchemaIDs] as [String: Any]
            } catch { result["stageError"] = error.localizedDescription }
        }
        emit(result)
    } catch {
        emit(["archive": url.lastPathComponent, "fatal": error.localizedDescription])
    }
}
