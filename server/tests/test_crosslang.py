"""v0.16.3: the phone and the server agree on the exact bytes they sign.

Two independent implementations of the same rule, each with its own passing tests, is
exactly the shape in which a mismatch survives: both sides are self-consistent and neither
test ever meets the other's bytes.

It had already happened here. `ReceiptRules.canonical` emitted its categories in
declaration order while `ruleconfig.canonical` sorted them, so every configuration this
server signed would have been refused by every phone - a feature that looked finished and
could not have worked once in the field.

`tests/fixtures/crosslang.json` is the shared artefact. Python writes it; the Kotlin test
`CrossLanguageFixtureTest` reads it and must agree byte for byte. The `kotlin_signature`
fields are captured from a real Kotlin test run, so the checks below are genuinely
"Android signed this, and Python accepts it" rather than Python marking its own homework.

The keys in the fixture are test keys, in the repository on purpose. The pilot's
configuration key is a different one and its private half is not here.
"""
import io
import json
import os
import unittest

from brain import paybox, protocol, ruleconfig, signed_request

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "crosslang.json")


def load():
    with io.open(FIXTURE, encoding="utf-8") as f:
        return json.load(f)


class CrossLanguageRequestTest(unittest.TestCase):
    def setUp(self):
        self.f = load()["request"]

    def test_the_canonical_target_is_the_one_in_the_fixture(self):
        self.assertEqual(self.f["canonical_target"],
                         signed_request.canonical_target(self.f["request_target"]))
        # and the raw target really was out of order, or this would prove nothing
        self.assertNotEqual(self.f["request_target"], self.f["canonical_target"])

    def test_the_signing_line_is_rebuilt_exactly(self):
        body = bytes.fromhex(self.f["body"])
        self.assertEqual(self.f["body_hash"], signed_request.body_hash(body))
        line = signed_request.signing_line(
            self.f["ts"], self.f["nonce"], self.f["body_hash"],
            self.f["method"], self.f["request_target"])
        self.assertEqual(self.f["signing_line"], line.decode("utf-8"))

    def test_a_signature_android_produced_is_accepted_here(self):
        """The direction that matters: the phone signs, the server verifies."""
        self.assertTrue(self.f["kotlin_signature"],
                        "capture it from the Kotlin test output before running this")
        line = signed_request.signing_line(
            self.f["ts"], self.f["nonce"], self.f["body_hash"],
            self.f["method"], self.f["request_target"])
        self.assertTrue(protocol.verify(self.f["public"], line, self.f["kotlin_signature"]))

    def test_an_android_signature_goes_through_the_real_verifier(self):
        headers = {
            "X-Prok-Identity": self.f["public"],
            "X-Prok-Timestamp": str(self.f["ts"]),
            "X-Prok-Nonce": self.f["nonce"],
            "X-Prok-Signature": self.f["kotlin_signature"],
        }
        who = signed_request.verify(headers, bytes.fromhex(self.f["body"]),
                                    signed_request.Nonces(), self.f["ts"],
                                    method=self.f["method"], path=self.f["request_target"])
        self.assertEqual(protocol.node_id(self.f["public"]), who)

    def test_the_same_android_signature_is_refused_at_another_endpoint(self):
        headers = {
            "X-Prok-Identity": self.f["public"],
            "X-Prok-Timestamp": str(self.f["ts"]),
            "X-Prok-Nonce": self.f["nonce"],
            "X-Prok-Signature": self.f["kotlin_signature"],
        }
        with self.assertRaises(signed_request.AuthError):
            signed_request.verify(headers, bytes.fromhex(self.f["body"]),
                                  signed_request.Nonces(), self.f["ts"],
                                  method="POST", path="/v1/pay/receipt")

    def test_the_query_is_inside_the_signature(self):
        headers = {
            "X-Prok-Identity": self.f["public"],
            "X-Prok-Timestamp": str(self.f["ts"]),
            "X-Prok-Nonce": self.f["nonce"],
            "X-Prok-Signature": self.f["kotlin_signature"],
        }
        other = self.f["request_target"].replace("payment=p1", "payment=p2")
        with self.assertRaises(signed_request.AuthError):
            signed_request.verify(headers, bytes.fromhex(self.f["body"]),
                                  signed_request.Nonces(), self.f["ts"],
                                  method=self.f["method"], path=other)


class CrossLanguageRuleConfigTest(unittest.TestCase):
    def setUp(self):
        self.f = load()["rules"]

    def test_the_canonical_configuration_is_rebuilt_exactly(self):
        canon = ruleconfig.canonical(self.f["version"], self.f["validFrom"], self.f["terms"])
        self.assertEqual(self.f["canonical"], canon.decode("utf-8"))
        # the categories are alphabetical, which is what the phone had to be taught
        self.assertLess(canon.decode("utf-8").index('"balance"'),
                        canon.decode("utf-8").index('"credit"'))

    def test_a_configuration_android_signed_is_accepted_here(self):
        self.assertTrue(self.f["kotlin_signature"],
                        "capture it from the Kotlin test output before running this")
        self.assertTrue(ruleconfig.verify(self.f["version"], self.f["validFrom"],
                                          self.f["terms"], self.f["kotlin_signature"],
                                          self.f["public"]))

    def test_the_server_would_publish_a_configuration_android_signed(self):
        import sqlite3
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        store = ruleconfig.RuleStore(db, self.f["public"])
        out = store.publish(self.f["version"], self.f["validFrom"], self.f["terms"],
                            self.f["kotlin_signature"], 1_758_400_000_001)
        self.assertTrue(out["ok"])
        self.assertEqual(self.f["version"], store.current()["version"])

    def test_the_response_the_phone_downloads_is_the_one_in_the_fixture(self):
        import sqlite3
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        store = ruleconfig.RuleStore(db, self.f["public"])
        store.publish(self.f["version"], self.f["validFrom"], self.f["terms"],
                      self.f["python_signature"], 1_758_400_000_001)
        served = json.dumps({"rules": store.current()}, ensure_ascii=False, sort_keys=True)
        self.assertEqual(self.f["server_response"], served,
                         "the phone parses this exact shape")

    def test_a_tampered_configuration_is_refused_by_both_signatures(self):
        broken = dict(self.f["terms"])
        broken["credit"] = list(broken["credit"]) + ["debite"]
        for sig in (self.f["python_signature"], self.f["kotlin_signature"]):
            self.assertFalse(ruleconfig.verify(self.f["version"], self.f["validFrom"],
                                               broken, sig, self.f["public"]))


class ActiveDestinationTest(unittest.TestCase):
    """v0.16.4: the phone and the server must route a payment to the same number.

    This is the second place the two languages could quietly disagree, and the consequence
    is worse than a refused configuration: the buyer pays a number the seller is no longer
    watching, or the seller refuses its own buyer's payment because it is still watching
    the old one. Neither side can see the other's answer, so nothing would report it.

    One divergence was already here. This server measured the cooling window from
    `stored_at` - when it happened to receive the claim - while the phone measures it from
    the `created_at` the seller signed. A phone offline for an hour would have moved to its
    new number while the Brain still sent buyers to the old one.
    """

    def setUp(self):
        self.f = load()["active_destination"]
        self.box = paybox.PayBox(":memory:")
        self.seller = "ee" * 16

    def put(self, claims):
        """Rows straight into the table: the signature path is covered elsewhere, and what
        is under test here is only which row comes back."""
        for c in claims:
            self.box.db.execute(
                "INSERT INTO pay_destinations(seller_id, rail, version, msisdn, dest_hash,"
                " created_at, stored_at, line, seller_pub) VALUES(?,?,?,?,?,?,?,?,?)",
                (self.seller, c["rail"], c["version"], c["msisdn"],
                 paybox.destination_hash(c["rail"], c["msisdn"]),
                 c["created_at"],
                 # deliberately NOT created_at: a server that used this would disagree
                 # with the phone, which cannot see it
                 c["created_at"] + 7 * 3600 * 1000,
                 "line-v%d" % c["version"], "aa" * 64))
        self.box.db.commit()

    def test_the_fixture_holds_the_cases_the_spec_lists(self):
        self.assertEqual(10, len(self.f["cases"]))
        self.assertEqual(10 * 60 * 1000, self.f["cooling_ms"])

    def test_every_case_agrees_with_the_phone(self):
        for case in self.f["cases"]:
            with self.subTest(case["name"]):
                box = paybox.PayBox(":memory:")
                self.box = box
                self.put(case["claims"])
                row = box.active_destination(self.seller, case["now"], self.f["cooling_ms"])
                self.assertIsNotNone(row, case["name"])
                self.assertEqual(case["expected_version"], int(row["version"]), case["name"])

    def test_the_window_is_measured_from_the_signed_timestamp_not_from_arrival(self):
        """The bug this replaced, stated on its own.

        Every row above was stored seven hours after it was created. If the window were
        measured from arrival, a claim well past its cooling period would still be treated
        as new and the old number would keep being handed out.
        """
        claims = [
            {"version": 1, "rail": "MTN_MOMO", "msisdn": "066111111", "created_at": 1_700_000_000_000},
            {"version": 2, "rail": "AIRTEL_MONEY", "msisdn": "055222222", "created_at": 1_700_000_060_000},
        ]
        self.put(claims)
        # an hour after the second claim was made: long past cooling by its own clock,
        # still "brand new" by the arrival clock
        now = 1_700_000_060_000 + 3600 * 1000
        row = self.box.active_destination(self.seller, now, 10 * 60 * 1000)
        self.assertEqual(2, int(row["version"]),
                         "the seller moved to Airtel an hour ago; buyers must be told")


class PinnedKeyTest(unittest.TestCase):
    """The key the pilot actually runs on. Public half only, on both sides."""

    def test_the_server_holds_a_real_configuration_public_key(self):
        from brain import app
        self.assertEqual(128, len(app.CONFIG_PUBLIC_KEY))
        bytes.fromhex(app.CONFIG_PUBLIC_KEY)
        self.assertNotEqual(app.CONFIG_PUBLIC_KEY, load()["rules"]["public"],
                            "the pilot key must not be the test key")

    def test_the_repository_contains_no_private_configuration_key(self):
        """A key in git is a key that is gone. Checked, not merely intended."""
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        suspicious = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames
                           if d not in (".git", "build", ".gradle", "dist", "__pycache__")]
            for name in filenames:
                if name.endswith((".pem", ".key", ".p8", ".p12", ".jks")):
                    suspicious.append(os.path.join(dirpath, name))
                    continue
                if not name.endswith((".py", ".kt", ".json", ".md", ".kts", ".ps1", ".xml")):
                    continue
                try:
                    with io.open(os.path.join(dirpath, name), encoding="utf-8",
                                 errors="ignore") as f:
                        text = f.read()
                except OSError:
                    continue
                # built at runtime so this very file does not trip its own check
                marker = "BEGIN " + "PRIVATE KEY"
                if marker in text:
                    suspicious.append(os.path.join(dirpath, name))
        self.assertEqual([], suspicious, "a private key must never be committed")

    def test_the_admin_tool_refuses_to_run_without_a_key_from_the_environment(self):
        from brain import publish_rules
        for var in ("PROK_CONFIG_PRIVATE_KEY_FILE", "PROK_CONFIG_PRIVATE_KEY"):
            os.environ.pop(var, None)
        with self.assertRaises(SystemExit):
            publish_rules.load_private_key()


if __name__ == "__main__":
    unittest.main()
