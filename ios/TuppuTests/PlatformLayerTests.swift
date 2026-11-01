import XCTest
import Shared

/// The iOS platform layer, driven from inside the app on a simulator (the app is the test host,
/// so the Keychain, the database and the HTTP engine are the app's own). The screens are not
/// touched: on a Mac without Metal they cannot be shown (see ../README.md), the data layer can.
///
/// The round trip against a server runs when the test runner is given one:
/// `TEST_RUNNER_TUPPU_SERVER_URL=https://localhost:8723 TEST_RUNNER_TUPPU_EMAIL=…
/// TEST_RUNNER_TUPPU_PASSWORD=… xcodebuild test …`; a self-signed certificate has to be
/// trusted in the simulator first (`xcrun simctl keychain booted add-root-cert cert.pem`).
final class PlatformLayerTests: XCTestCase {

    private var services: IosServices { IosServices.shared }

    func testKeychainKeepsATokenPerAccount() {
        let secrets = services.secrets
        secrets.setToken(accountId: "xctest-a", token: "secret-a")
        secrets.setToken(accountId: "xctest-b", token: "secret-b")
        XCTAssertEqual(secrets.token(accountId: "xctest-a"), "secret-a")
        XCTAssertEqual(secrets.token(accountId: "xctest-b"), "secret-b")

        secrets.setToken(accountId: "xctest-a", token: "replaced")
        XCTAssertEqual(secrets.token(accountId: "xctest-a"), "replaced")

        secrets.setToken(accountId: "xctest-a", token: nil)
        secrets.setToken(accountId: "xctest-b", token: nil)
        XCTAssertNil(secrets.token(accountId: "xctest-a"))
        XCTAssertNil(secrets.token(accountId: "xctest-b"))
    }

    func testAccountsLoadedAtStart() {
        // startIosApp() ran in the app's init: the registry has read its table.
        let accounts = services.registry.snapshot()
        XCTAssertNotNil(accounts)
    }

    func testLoginCreateListAddItemAndSync() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["TUPPU_SERVER_URL"], let email = env["TUPPU_EMAIL"], let password = env["TUPPU_PASSWORD"] else {
            throw XCTSkip("TUPPU_SERVER_URL, TUPPU_EMAIL and TUPPU_PASSWORD name a server to log in to")
        }

        let accountId = try await services.auth.login(
            serverUrl: server, email: email, password: password, allowSelfSignedCerts: false,
            expect: LoginExpectationAnyone.shared
        )
        let account = services.registry.get(id: accountId)
        XCTAssertEqual(account?.email, email)
        XCTAssertEqual(account?.signedIn, true)
        XCTAssertNotNil(services.secrets.token(accountId: accountId), "the token is on the Keychain")

        let listId = try await services.lists.create(
            accountId: accountId, name: "XCTest \(UUID().uuidString.prefix(8))",
            kind: ListKind.shared.DEFAULT, currency: nil, guestNames: []
        )
        let itemId = try await services.items.createItem(listId: listId, name: "Milk", status: Status.todo)
        let created = try await services.lists.getById(listId: listId)
        XCTAssertEqual(created?.dirty, true, "not pushed yet")

        let result = try await services.syncer.syncNow(fullLists: [])
        guard let success = result as? SyncResultSuccess else {
            XCTFail("sync did not succeed: \(result)")
            return
        }
        XCTAssertGreaterThanOrEqual(Int(success.pushedLists), 1)
        XCTAssertGreaterThanOrEqual(Int(success.pushedItems), 1)
        let pushedList = try await services.lists.getById(listId: listId)
        let pushedItem = try await services.items.getById(itemId: itemId)
        XCTAssertEqual(pushedList?.dirty, false, "the list reached the server")
        XCTAssertEqual(pushedItem?.dirty, false, "the item reached the server")

        _ = try await services.items.setStatus(itemId: itemId, status: Status.checked)
        let again = try await services.syncer.syncNow(fullLists: [])
        XCTAssertTrue(again is SyncResultSuccess, "a second sync pushes the edit: \(again)")
        let checkedItem = try await services.items.getById(itemId: itemId)
        XCTAssertEqual(checkedItem?.dirty, false)
        XCTAssertEqual(checkedItem?.status.value, Status.checked.wireValue)

        // Leaves the device as it was found: the account, its lists and its token go.
        try await services.auth.removeAccount(accountId: accountId)
        XCTAssertNil(services.registry.get(id: accountId))
        XCTAssertNil(services.secrets.token(accountId: accountId))
    }
}
