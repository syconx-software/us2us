import Foundation
import Testing

@testable import Us2usKit

@Suite("Message requests (§6.4)")
struct MessageRequestTests {
    let theirKey = Data.id(7)

    @Test func deliveryRules() {
        #expect(MessageRequestPolicy.delivery(state: .blocked, theirAccessKey: theirKey) == nil)

        let outgoing = MessageRequestPolicy.delivery(state: .outgoingRequest, theirAccessKey: nil)
        #expect(outgoing?.authorization == .proofOfWork)
        #expect(outgoing?.includeSenderUsername == true)

        let accepted = MessageRequestPolicy.delivery(state: .accepted, theirAccessKey: theirKey)
        #expect(accepted?.authorization == .accessKey(theirKey))
        #expect(accepted?.includeSenderUsername == false)

        // Accepted, but the contact's key has not arrived yet.
        let pending = MessageRequestPolicy.delivery(state: .accepted, theirAccessKey: nil)
        #expect(pending?.authorization == .proofOfWork)
        #expect(pending?.includeSenderUsername == false)
    }

    @Test("no receipts or typing for requests; delivery receipts always otherwise (§8.4)")
    func receiptsAndTyping() {
        for state in [ConversationRequestState.incomingRequest, .outgoingRequest, .blocked] {
            #expect(!MessageRequestPolicy.maySendDeliveryReceipts(state: state))
            #expect(!MessageRequestPolicy.maySendReadReceipts(state: state, userEnabled: true))
            #expect(!MessageRequestPolicy.maySendTypingIndicators(state: state, userEnabled: true))
        }
        #expect(MessageRequestPolicy.maySendDeliveryReceipts(state: .accepted))
        #expect(MessageRequestPolicy.maySendReadReceipts(state: .accepted, userEnabled: true))
        #expect(!MessageRequestPolicy.maySendReadReceipts(state: .accepted, userEnabled: false))
        #expect(!MessageRequestPolicy.maySendTypingIndicators(state: .accepted, userEnabled: false))
    }

    @Test func stateTransitions() {
        let text = BRPContent.text("hi")
        let accept = BRPContent.messageRequestResponse(.accept)
        #expect(MessageRequestPolicy.state(afterReceiving: text, current: nil) == .incomingRequest)
        #expect(MessageRequestPolicy.state(afterReceiving: accept, current: .outgoingRequest) == .accepted)
        #expect(MessageRequestPolicy.state(afterReceiving: text, current: .outgoingRequest) == .outgoingRequest)
        #expect(MessageRequestPolicy.state(afterReceiving: accept, current: .blocked) == .blocked)
        #expect(!MessageRequestPolicy.shouldDeliver(state: .blocked))
        #expect(MessageRequestPolicy.shouldDeliver(state: nil))
    }

    @Test("accepting sends ACCEPT and our access key; the requester answers with its key")
    func acceptance() throws {
        let ours = AccessKey.generate()
        let messages = MessageRequestPolicy.acceptanceMessages(ourAccessKey: ours)
        #expect(messages.count == 2)
        #expect(messages[0].control.requestResponse.type == .accept)
        #expect(messages[1].control.accessKey.accessKey == ours)
        for message in messages { try message.validateForSending() }
        #expect(MessageRequestPolicy.messagesAfterAcceptance(ourAccessKey: ours)[0].control.accessKey.accessKey == ours)
    }

    @Test("accessKeyHash = SHA-256(accessKey)")
    func accessKeyHash() {
        let key = AccessKey.generate()
        #expect(key.count == 16)
        #expect(AccessKey.hash(key) == key.sha256Digest)
        #expect(AccessKey.generate() != key)
    }
}
