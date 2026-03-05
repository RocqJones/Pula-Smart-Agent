import Foundation
import Shared

// Wraps Kotlin's `suspend` functions in Swift `async` functions.
// The KMP `SurveySyncEngine` is written in terms of these protocols, so our
// fakes in the test target can conform to these instead of dealing with
// completion handlers.
//
// We also use this to expose a cleaner API to the rest of the Swift codebase.

protocol SurveyApiAsync: Sendable {
    func uploadSurvey(response: SurveyResponse) async throws
    func uploadAttachment(attachment: Attachment) async throws
}

protocol SurveyRepositoryAsync: Sendable {
    func getPendingSurveys() async throws -> [SurveyResponse]
    func saveSurvey(response: SurveyResponse) async throws
    func markAsSynced(id: String) async throws
    func markAsFailed(id: String, error: any SyncError) async throws
    func incrementRetry(id: String) async throws
    func pinRetryToMax(id: String) async throws
}

protocol AttachmentRepositoryAsync: Sendable {
    func getUploadedAttachments() async throws -> [Attachment]
    func markAsUploaded(id: String) async throws
    func markAsFailed(id: String, error: any SyncError) async throws
    func incrementRetry(id: String) async throws
}

protocol NetworkMonitorAsync: Sendable {
    func isConnected() async -> Bool
}

