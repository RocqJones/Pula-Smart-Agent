import Foundation
import Shared

// Adapts the KMP `AttachmentRepository` (completion handlers) to the `AttachmentRepositoryAsync` protocol (async/await).
struct AttachmentRepositoryAsyncAdapter: AttachmentRepositoryAsync {
    private let repo: AttachmentRepository

    init(_ repo: AttachmentRepository) {
        self.repo = repo
    }

    func getUploadedAttachments() async throws -> [Attachment] {
        try await withCheckedThrowingContinuation { continuation in
            repo.getUploadedAttachments { attachments, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: attachments ?? [])
                }
            }
        }
    }

    func markAsUploaded(id: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            repo.markAsUploaded(id: id) { _, error in
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
}

