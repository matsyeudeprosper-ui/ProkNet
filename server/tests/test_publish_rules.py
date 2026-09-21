"""v0.16.3: the admin tool that is the only thing allowed to touch the config key.

The workflow this proves, end to end:

    admin, offline, with the private key
        -> validates and signs the canonical bytes
        -> hands the Brain a signed configuration
        -> Brain verifies against the PUBLIC key before storing
        -> phone downloads and verifies again against the key pinned in the APK

The last step is the Kotlin side's; everything before it is here. The key never appears on
a command line, so it cannot end up in shell history or a process listing - and the tool
refuses to run rather than falling back to anything.
"""
import io
import json
import os
import sqlite3
import tempfile
import unittest

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from brain import publish_rules, ruleconfig


def new_key():
    k = ec.generate_private_key(ec.SECP256R1())
    pem = k.private_bytes(serialization.Encoding.PEM,
                          serialization.PrivateFormat.PKCS8,
                          serialization.NoEncryption()).decode("ascii")
    n = k.public_key().public_numbers()
    pub = (n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")).hex()
    return k, pem, pub


class PublishRulesTest(unittest.TestCase):
    def setUp(self):
        self.key, self.pem, self.pub = new_key()
        self.dir = tempfile.mkdtemp()
        self.pem_path = os.path.join(self.dir, "config_key.pem")
        with io.open(self.pem_path, "w", encoding="ascii") as f:
            f.write(self.pem)
        self.db_path = os.path.join(self.dir, "brain.db")
        self.rules_path = os.path.join(self.dir, "rules.json")
        self.write_rules({"credit": ["fonds arrives"], "currency": ["XAF"]})
        for var in ("PROK_CONFIG_PRIVATE_KEY_FILE", "PROK_CONFIG_PRIVATE_KEY"):
            os.environ.pop(var, None)

    def tearDown(self):
        for var in ("PROK_CONFIG_PRIVATE_KEY_FILE", "PROK_CONFIG_PRIVATE_KEY"):
            os.environ.pop(var, None)

    def write_rules(self, terms, version=2, valid_from=1_758_400_000_000):
        with io.open(self.rules_path, "w", encoding="utf-8") as f:
            json.dump({"version": version, "validFrom": valid_from, "terms": terms}, f)

    def store(self):
        db = sqlite3.connect(self.db_path)
        db.row_factory = sqlite3.Row
        return ruleconfig.RuleStore(db, self.pub)

    # ---- where the key comes from ----------------------------------------------------

    def test_the_key_is_read_from_a_file_named_by_the_environment(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        k = publish_rules.load_private_key()
        self.assertEqual(self.pub, publish_rules.public_hex(k))

    def test_the_key_may_be_the_variable_itself_when_a_file_is_impossible(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY"] = self.pem
        self.assertEqual(self.pub, publish_rules.public_hex(publish_rules.load_private_key()))

    def test_a_file_wins_over_the_inline_variable(self):
        other_pem = new_key()[1]
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        os.environ["PROK_CONFIG_PRIVATE_KEY"] = other_pem
        self.assertEqual(self.pub, publish_rules.public_hex(publish_rules.load_private_key()))

    def test_with_no_key_it_refuses_rather_than_doing_something_else(self):
        with self.assertRaises(SystemExit):
            publish_rules.load_private_key()

    def test_the_key_is_never_an_argument(self):
        # a key on the command line is in the shell history and in every process listing
        import argparse
        parser_args = publish_rules.main.__doc__ or ""
        self.assertNotIn("--key", parser_args)
        with self.assertRaises(SystemExit):
            publish_rules.main(["--private-key", "x", self.rules_path])

    # ---- publishing -------------------------------------------------------------------

    def test_it_signs_and_publishes_a_configuration_the_brain_accepts(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        self.assertEqual(0, publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub]))
        current = self.store().current()
        self.assertEqual(2, current["version"])
        self.assertEqual(["fonds arrives"], current["terms"]["credit"])
        # the stored signature is the thing the phone will check, so it must verify
        self.assertTrue(ruleconfig.verify(current["version"], current["validFrom"],
                                          current["terms"], current["signature"], self.pub))

    def test_a_dry_run_signs_and_changes_nothing(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        self.assertEqual(0, publish_rules.main([self.rules_path, "--db", self.db_path, "--dry-run",
                                            "--config-public-key", self.pub]))
        self.assertFalse(os.path.exists(self.db_path), "a dry run must not create anything")

    def test_a_configuration_out_of_bounds_fails_at_the_desk_not_on_the_phones(self):
        self.write_rules({"credit": ["x" * (ruleconfig.MAX_TERM_LENGTH + 1)]})
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        with self.assertRaises(ruleconfig.ConfigError):
            publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])

    def test_a_term_that_would_need_escaping_is_refused_before_it_is_signed(self):
        # otherwise the server signs happily and every phone silently refuses
        self.write_rules({"credit": ['say "recu"']})
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        with self.assertRaises(ruleconfig.ConfigError):
            publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])

    def test_an_older_version_cannot_be_published_over_a_newer_one(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])
        self.write_rules({"credit": ["autre chose"]}, version=1)
        with self.assertRaises(ruleconfig.ConfigError):
            publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])
        self.assertEqual(["fonds arrives"], self.store().current()["terms"]["credit"])

    def test_a_second_publisher_cannot_overwrite_the_first(self):
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])

        # somebody else's key, signing a perfectly well-formed configuration
        other_key, other_pem, _ = new_key()
        other_path = os.path.join(self.dir, "other.pem")
        with io.open(other_path, "w", encoding="ascii") as f:
            f.write(other_pem)
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = other_path
        self.write_rules({"credit": ["envoye"]}, version=3)
        # the tool notices before it signs anything: this is not the key the deployment pins
        with self.assertRaises(SystemExit):
            publish_rules.main([self.rules_path, "--db", self.db_path,
                                "--config-public-key", self.pub])
        self.assertEqual(["fonds arrives"], self.store().current()["terms"]["credit"],
                         "only the pinned key may publish")

    def test_the_store_refuses_a_foreign_signature_even_if_the_tool_is_bypassed(self):
        # the tool's check is a courtesy; this is the one that has to hold
        os.environ["PROK_CONFIG_PRIVATE_KEY_FILE"] = self.pem_path
        publish_rules.main([self.rules_path, "--db", self.db_path,
                            "--config-public-key", self.pub])
        other_key = new_key()[0]
        terms = ruleconfig.validate(3, 1_758_400_000_000, {"credit": ["envoye"]})
        foreign = ruleconfig.sign(3, 1_758_400_000_000, terms, other_key)
        with self.assertRaises(ruleconfig.ConfigError):
            self.store().publish(3, 1_758_400_000_000, terms, foreign, 1_758_400_000_001)
        self.assertEqual(["fonds arrives"], self.store().current()["terms"]["credit"])

    def test_the_running_brain_never_needs_the_private_key(self):
        """The whole point of the split, stated as a test."""
        from brain import app
        with io.open(app.__file__, encoding="utf-8") as f:
            source = f.read()
        self.assertNotIn("PROK_CONFIG_PRIVATE_KEY", source)
        self.assertNotIn("load_private_key", source)
        # the server holds the public half and verifies with it
        self.assertIn("CONFIG_PUBLIC_KEY", source)


if __name__ == "__main__":
    unittest.main()
