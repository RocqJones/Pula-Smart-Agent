import Foundation
import Shared

// Adapts the KMP `SurveyApi` (completion handlers) to the `SurveyApiAsync` protocol (async/await).
struct SurveyApiAsyncAdapter: SurveyApiAsync {
    private let api: SurveyApi

    init(_ api: SurveyApi) {
        self.api = api
    }

    func uploadSurvey(response: SurveyResponse) async throws {
        try await withCheckedThrowingContinuation { continuation in
            api.uploadSurvey(response: response) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func uploadAttachment(attachment: Attachment) async throws {
        try await withCheckedThrowingContinuation { continuation in
            api.uploadAttachment(attachment: attachment) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }
}

