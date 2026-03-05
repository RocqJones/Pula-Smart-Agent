import Foundation
import Shared

// URLSession implementation of the KMP SurveyApi interface.
final class SurveyApiImpl: NSObject, SurveyApi {
    func uploadSurvey(response: SurveyResponse) async throws -> Any? {
        <#code#>
    }
    
    func uploadSurvey(response: SurveyResponse, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        <#code#>
    }
    
    func uploadAttachment(attachment: Attachment) async throws -> Any? {
        <#code#>
    }
    
    func uploadAttachment(attachment: Attachment, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        <#code#>
    }


    private let session: URLSession
    private let baseURL: URL

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func uploadSurvey(
        response: Shared.SurveyResponse,
        completionHandler: @escaping (KotlinUnit?, Error?) -> Void
    ) {
        Task {
            do {
                let body = try JSONEncoder().encode(response.toDTO())
                try await perform(try buildRequest(path: "/surveys", body: body))
                completionHandler(KotlinUnit(), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    func uploadAttachment(
        attachment: Shared.Attachment,
        completionHandler: @escaping (KotlinUnit?, Error?) -> Void
    ) {
        Task {
            do {
                let boundary = UUID().uuidString
                var request  = URLRequest(url: baseURL.appendingPathComponent("/attachments"))
                request.httpMethod = "POST"
                request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
                let fileData = (try? Data(contentsOf: URL(fileURLWithPath: attachment.localPath))) ?? Data()
                request.httpBody = buildMultipart(boundary: boundary, fileData: fileData, attachment: attachment)
                try await perform(request)
                completionHandler(KotlinUnit(), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    private func buildRequest(path: String, body: Data) throws -> URLRequest {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody  = body
        return request
    }

    private func perform(_ request: URLRequest) async throws {
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw NSError(domain: "SurveyApi", code: -1)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw HttpException(code: Int32(http.statusCode)) as! any Error
        }
    }

    private func buildMultipart(boundary: String, fileData: Data, attachment: Shared.Attachment) -> Data {
        var body = Data()
        func append(_ s: String) { body.append(s.data(using: .utf8)!) }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"\(attachment.id).jpg\"\r\n")
        append("Content-Type: image/jpeg\r\n\r\n")
        body.append(fileData)
        append("\r\n--\(boundary)--\r\n")
        return body
    }
}

private struct SurveyDTO: Encodable {
    let id: String
    let farmerId: String
}

private extension Shared.SurveyResponse {
    func toDTO() -> SurveyDTO { SurveyDTO(id: id, farmerId: farmerId) }
}
