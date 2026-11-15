import Foundation

struct ExperimentPlan: Codable {
    struct Round: Codable {
        var order: Int
        var block: Int
        var configuration: TrialConfiguration
    }
    var protocolName: String
    var seed: Int
    var blocks: Int
    var rounds: [Round]
    enum CodingKeys: String, CodingKey { case protocolName = "protocol", seed, blocks, rounds }
    func validate() throws {
        guard !rounds.isEmpty,rounds.count<=500,blocks>0,Set(rounds.map(\.order)).count == rounds.count else { throw PlanError.invalid }
        for (i,r) in rounds.enumerated() {
            guard r.order == i+1,r.block>0,r.block<=blocks,r.configuration.validationError == nil else { throw PlanError.invalid }
        }
    }
    enum PlanError: Error { case invalid }
}
