import Foundation
import Shared

// Adapts the KMP `SurveyRepository` (completion handlers) to the `SurveyRepositoryAsync` protocol (async/await).
struct SurveyRepositoryAsyncAdapter: SurveyRepositoryAsync {
    private let repo: SurveyRepository

    init(_ repo: SurveyRepository) {
        self.repo = repo
    }

    func getPendingSurveys() async throws -> [SurveyResponse] {
        try await withCheckedThrowingContinuation { continuation in
            repo.getPendingSurveys { surveys, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: surveys ?? [])
                }
            }
        }
    }

    func saveSurvey(response: SurveyResponse) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.saveSurvey(response: response) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func markAsSynced(id: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.markAsSynced(id: id) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func markAsFailed(id: String, error: any SyncError) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.markAsFailed(id: id, error: error) { _, err in
                if let err {
                    continuation.resume(throwing: err)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func incrementRetry(id: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.incrementRetry(id: id) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func pinRetryToMax(id: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.pinRetryToMax(id: id) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }
}

