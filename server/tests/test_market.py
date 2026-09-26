"""v0.19.0: Prok Market.

The rules under test: a listing is published only after its fee cleared AND a moderator
approved it; a rejected listing never leaves the seller charged; one operator transaction
pays one invoice, exactly its amount, within 24 h; a boost is paid, zoned, labelled and
never displaces the organic results; prohibited content and private data are refused at
submission with a reason; and every price equals the fixture the phone also reads.
"""
import os
import sqlite3
import tempfile
import unittest

from brain import market
from brain.market import (AWAITING_PAYMENT, AWAITING_REVIEW, CANCELLED, DAY_MS, EXPIRED, HIDDEN, HOUR_MS,
                          INVOICE_TEXT, INVOICE_TTL_MS, LISTING_TEXT, Market, MarketError, OPEN, PAID,
                          PUBLISHED, REFUNDED, REFUND_REQUESTED, REJECTED, WITHDRAWN)
from brain.ledger import msisdn_hash

T0 = 1_700_000_000_000
SELLER = "aa" * 16
BUYER = "bb" * 16
OTHER = "cc" * 16
OPERATOR = "dd" * 16
TREASURER = "ee" * 16
TREASURER2 = "ff" * 16
PHONE = "066123456"
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 100
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "market_prices.txt")


class Posted:
    """A stand-in for the ledger's `_post`: records every call."""
    def __init__(self):
        self.rows = []

    def __call__(self, now, kind, debit, credit, amount, ref="", memo="", actor=""):
        self.rows.append({"now": now, "kind": kind, "debit": debit, "credit": credit, "amount": amount, "ref": ref, "memo": memo})
        return "p%d" % len(self.rows)


def fresh(media_dir=None):
    db = sqlite3.connect(":memory:", check_same_thread=False)
    posted = Posted()
    M = Market(db, is_operator=lambda w: w == OPERATOR, is_treasury=lambda w: w in (TREASURER, TREASURER2),
               media_dir=media_dir or tempfile.mkdtemp(prefix="prokmarket"), ledger_post=posted)
    M.posted = posted
    return M


def fields(**kw):
    f = {"title": "Vélo VTT 26 pouces", "description": "Bon état, freins neufs. Remise en main propre.", "category": "OTHER",
         "price_centimes": 2_500_000, "condition": "GOOD", "neighbourhood": "Bacongo", "lat": 4.2812, "lon": 15.2613,
         "pickup_options": "Marché Total"}
    f.update(kw)
    return f


def seller(M, who=SELLER, phone=PHONE, now=T0):
    return M.register_seller(who, phone, True, "", now)


def submit(M, who=SELLER, now=T0, pay_with="INVOICE", **kw):
    return M.submit_listing(who, fields(**kw), now, pay_with=pay_with, rail="MTN")


def pay(M, invoice_id, now=T0, txn="TXN-1", amount=None, payer=PHONE, treasurer=TREASURER):
    inv = M._invoice(invoice_id)
    return M.confirm_invoice(treasurer, invoice_id, txn, amount if amount is not None else int(inv["amount_centimes"]),
                             msisdn_hash(payer), now, rail="MTN")


def published(M, who=SELLER, now=T0, txn="TXN-1", **kw):
    """The whole path: submit, pay, approve. Returns the listing id."""
    out = submit(M, who=who, now=now, **kw)
    lid = out["listing"]["id"]
    pay(M, out["invoice"]["id"], now=now, txn=txn)
    M.review(OPERATOR, lid, True, "", now)
    return lid


class PriceFixtureTest(unittest.TestCase):

    def test_every_price_and_text_equals_the_fixture(self):
        with open(FIXTURE, encoding="utf-8") as f:
            fx = market.parse_prices_fixture(f.readlines())
        self.assertEqual(market.DEFAULT_PRICES, fx["prices"])
        self.assertEqual(INVOICE_TEXT, fx["invoice_text"])
        self.assertEqual(LISTING_TEXT, fx["listing_text"])
        # and rendering the defaults gives back the same lines the file holds
        with open(FIXTURE, encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        self.assertEqual(lines, market.render_prices_fixture(market.DEFAULT_PRICES))

    def test_the_launch_numbers_are_10_3_exactly(self):
        M = fresh()
        p = M.prices_at(T0)["prices"]
        self.assertEqual((10_000, 30, 5), (p["POST"]["centimes"], p["POST"]["duration_days"], p["POST"]["photos"]))
        self.assertEqual((20_000, 7), (p["BOOST"]["centimes"], p["BOOST"]["duration_days"]))
        self.assertEqual((40_000, 90, 5), (p["PACKAGE5"]["centimes"], p["PACKAGE5"]["duration_days"], p["PACKAGE5"]["slots"]))
        self.assertEqual((200_000, 30, 30, 1), (p["STOREFRONT"]["centimes"], p["STOREFRONT"]["duration_days"], p["STOREFRONT"]["slots"], p["STOREFRONT"]["catalog"]))
        self.assertEqual((0, 10_000, 100), (p["VOUCHER"]["centimes"], p["VOUCHER"]["face_value"], p["VOUCHER"]["limit"]))

    def test_a_price_change_is_a_dated_version_and_old_invoices_keep_their_amount(self):
        M = fresh()
        seller(M)
        inv = submit(M)["invoice"]
        self.assertEqual(10_000, inv["amount"])
        new = dict(market.DEFAULT_PRICES)
        new["POST"] = {"centimes": 15_000, "duration_days": 30, "photos": 5}
        with self.assertRaises(MarketError):
            M.set_prices(SELLER, T0 + DAY_MS, new, T0)
        M.set_prices(OPERATOR, T0 + DAY_MS, new, T0, note="pilot test")
        self.assertEqual(10_000, M.prices_at(T0)["prices"]["POST"]["centimes"], "not yet effective")
        self.assertEqual(15_000, M.prices_at(T0 + DAY_MS)["prices"]["POST"]["centimes"])
        self.assertEqual(2, len(M.price_log()))
        self.assertEqual(10_000, M.get_invoice(SELLER, inv["id"], T0 + 2 * DAY_MS)["amount"], "the old invoice is not reinterpreted")
        self.assertEqual(15_000, submit(M, now=T0 + 2 * DAY_MS, title="Autre")["invoice"]["amount"])


class PublicationTest(unittest.TestCase):

    def test_publish_requires_payment_and_approval(self):
        M = fresh()
        seller(M)
        out = submit(M)
        lid, inv = out["listing"]["id"], out["invoice"]
        self.assertEqual(AWAITING_PAYMENT, out["listing"]["state"])
        self.assertEqual(OPEN, inv["state"])
        self.assertTrue(inv["reference"].startswith("PK-") and len(inv["reference"]) == 9)
        self.assertIn(inv["reference"], inv["instruction"])
        # a moderator cannot approve what was not paid
        with self.assertRaises(MarketError):
            M.review(OPERATOR, lid, True, "", T0)
        pay(M, inv["id"])
        self.assertEqual(AWAITING_REVIEW, M.get_listing(lid, T0, who=SELLER)["state"])
        # paid but not approved: not public
        with self.assertRaises(MarketError):
            M.get_listing(lid, T0)
        self.assertEqual(0, len(M.search(T0)["items"]))
        M.review(OPERATOR, lid, True, "", T0 + HOUR_MS)
        v = M.get_listing(lid, T0 + HOUR_MS)
        self.assertEqual(PUBLISHED, v["state"])
        self.assertEqual(T0 + HOUR_MS + 30 * DAY_MS, v["expires_at"], "expiry = published_at + 30 d")
        self.assertEqual(1, len(M.search(T0 + HOUR_MS)["items"]))
        # revenue was posted once, from the rail's float into market:revenue, keyed by the invoice
        self.assertEqual(1, len(M.posted.rows))
        self.assertEqual(("MARKET_FEE", "float:mtn", "market:revenue", 10_000, inv["id"], "POST"),
                         tuple(M.posted.rows[0][k] for k in ("kind", "debit", "credit", "amount", "ref", "memo")))

    def test_only_a_moderator_reviews_and_a_stranger_gets_an_audit_row(self):
        M = fresh()
        seller(M)
        out = submit(M)
        pay(M, out["invoice"]["id"])
        with self.assertRaises(MarketError) as cm:
            M.review(BUYER, out["listing"]["id"], True, "", T0)
        self.assertEqual(403, cm.exception.code)
        self.assertTrue(any(r["action"] == "market.review" and not r["allowed"] for r in M.audit_rows(TREASURER, T0)))

    def test_a_seller_needs_phone_and_adult_attestation(self):
        M = fresh()
        with self.assertRaises(MarketError) as cm:
            submit(M)
        self.assertEqual(market.NOT_SELLER, cm.exception.reason)
        M.register_seller(SELLER, PHONE, False, "", T0)
        with self.assertRaises(MarketError) as cm:
            submit(M)
        self.assertEqual(market.NOT_ADULT, cm.exception.reason)
        with self.assertRaises(MarketError):
            M.register_seller(SELLER, "12", True, "", T0)

    def test_rejection_leaves_the_seller_uncharged_via_refund_request(self):
        M = fresh()
        seller(M)
        out = submit(M)
        pay(M, out["invoice"]["id"])
        M.review(OPERATOR, out["listing"]["id"], False, "photo floue", T0)
        l = M.get_listing(out["listing"]["id"], T0, who=SELLER)
        self.assertEqual(REJECTED, l["state"])
        self.assertEqual("photo floue", l["review_note"])
        self.assertEqual(REFUND_REQUESTED, M.get_invoice(SELLER, out["invoice"]["id"], T0)["state"])
        self.assertEqual("Remboursement en cours", M.get_invoice(SELLER, out["invoice"]["id"], T0)["text"])

    def test_trusted_seller_publishes_at_once_with_a_review_flag(self):
        M = fresh()
        seller(M)
        M.set_trusted(OPERATOR, SELLER, True, T0)
        out = submit(M)
        pay(M, out["invoice"]["id"])
        l = M.get_listing(out["listing"]["id"], T0, who=SELLER)
        self.assertEqual(PUBLISHED, l["state"])
        self.assertTrue(l["review_flag"])
        self.assertEqual(1, len(M.review_queue(OPERATOR, T0)), "still in the risk queue")
        # a rejection in the risk queue takes it down and undoes the charge
        M.review(OPERATOR, out["listing"]["id"], False, "no", T0 + HOUR_MS)
        self.assertEqual(REJECTED, M.get_listing(out["listing"]["id"], T0, who=SELLER)["state"])
        self.assertEqual(REFUND_REQUESTED, M.get_invoice(SELLER, out["invoice"]["id"], T0)["state"])

    def test_material_edit_goes_back_to_review_and_keeps_expiry(self):
        M = fresh()
        seller(M)
        lid = published(M)
        exp = M.get_listing(lid, T0)["expires_at"]
        out = M.edit_listing(SELLER, lid, {"price_centimes": 2_000_000}, T0 + DAY_MS)
        self.assertFalse(out["material"])
        self.assertEqual(PUBLISHED, out["listing"]["state"])
        out = M.edit_listing(SELLER, lid, {"description": "Bon état, freins neufs, pneus neufs."}, T0 + 2 * DAY_MS)
        self.assertTrue(out["material"])
        self.assertEqual(AWAITING_REVIEW, out["listing"]["state"])
        self.assertEqual(0, len(M.search(T0 + 2 * DAY_MS)["items"]))
        M.review(OPERATOR, lid, True, "", T0 + 3 * DAY_MS)
        self.assertEqual(exp, M.get_listing(lid, T0 + 3 * DAY_MS)["expires_at"], "an edit never buys time")

    def test_withdraw_gives_no_refund_and_cancels_an_open_invoice(self):
        M = fresh()
        seller(M)
        lid = published(M)
        M.withdraw_listing(SELLER, lid, T0 + 5 * DAY_MS)
        self.assertEqual(WITHDRAWN, M.get_listing(lid, T0, who=SELLER)["state"])
        self.assertEqual(PAID, M.my_invoices(SELLER, T0)[0]["state"], "no refund for time served")
        self.assertEqual(1, len(M.posted.rows))
        out = submit(M, title="Autre chose")
        M.withdraw_listing(SELLER, out["listing"]["id"], T0)
        self.assertEqual(CANCELLED, M.get_invoice(SELLER, out["invoice"]["id"], T0)["state"])
        with self.assertRaises(MarketError):
            M.withdraw_listing(SELLER, lid, T0)

    def test_renew_is_a_new_invoice_and_expiry_then_moves(self):
        M = fresh()
        seller(M)
        lid = published(M)
        exp = M.get_listing(lid, T0)["expires_at"]
        inv = M.renew_listing(SELLER, lid, "MTN", T0 + 10 * DAY_MS)["invoice"]
        self.assertEqual(10_000, inv["amount"])
        self.assertEqual(exp, M.get_listing(lid, T0 + 10 * DAY_MS)["expires_at"], "nothing moves before the money")
        pay(M, inv["id"], now=T0 + 10 * DAY_MS + HOUR_MS, txn="TXN-2")
        self.assertEqual(exp + 30 * DAY_MS, M.get_listing(lid, T0 + 10 * DAY_MS + HOUR_MS)["expires_at"])
        # an expired listing renewed comes back for 30 days from the payment
        M.sweep(exp + 31 * DAY_MS)
        self.assertEqual(EXPIRED, M.get_listing(lid, exp + 31 * DAY_MS, who=SELLER)["state"])
        inv = M.renew_listing(SELLER, lid, "MTN", exp + 31 * DAY_MS)["invoice"]
        pay(M, inv["id"], now=exp + 31 * DAY_MS + HOUR_MS, txn="TXN-3")
        l = M.get_listing(lid, exp + 31 * DAY_MS + HOUR_MS)
        self.assertEqual(PUBLISHED, l["state"])
        self.assertEqual(exp + 61 * DAY_MS + HOUR_MS, l["expires_at"])

    def test_a_stranger_cannot_edit_or_withdraw(self):
        M = fresh()
        seller(M)
        lid = published(M)
        with self.assertRaises(MarketError) as cm:
            M.edit_listing(BUYER, lid, {"title": "x"}, T0)
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(MarketError):
            M.withdraw_listing(BUYER, lid, T0)


class VoucherTest(unittest.TestCase):

    def test_voucher_only_for_verified_sellers_once_and_off_after_the_limit(self):
        M = fresh()
        seller(M)
        self.assertFalse(M.voucher_available(SELLER, T0), "not verified yet")
        M.verify_seller(TREASURER, SELLER, T0)
        self.assertTrue(M.voucher_available(SELLER, T0))
        out = submit(M, pay_with="VOUCHER")
        self.assertEqual("VOUCHER", out["paid_with"])
        self.assertEqual(AWAITING_REVIEW, out["listing"]["state"])
        self.assertNotIn("invoice", out)
        self.assertFalse(M.voucher_available(SELLER, T0), "one per seller")
        with self.assertRaises(MarketError) as cm:
            submit(M, pay_with="VOUCHER", title="Deuxième")
        self.assertEqual(market.NO_VOUCHER, cm.exception.reason)
        M.review(OPERATOR, out["listing"]["id"], True, "", T0)
        self.assertEqual(PUBLISHED, M.get_listing(out["listing"]["id"], T0)["state"])
        self.assertEqual(0, len(M.posted.rows), "a voucher posts no revenue")
        # the limit: 100 vouchers granted and the 101st verified seller gets none
        for i in range(1, 100):
            node = "%032x" % i
            M.register_seller(node, "06%07d" % i, True, "", T0)
            M.verify_seller(TREASURER, node, T0)
            self.assertTrue(M.voucher_available(node, T0), "seller %d" % i)
            M.submit_listing(node, fields(title="Objet %d" % i), T0, pay_with="VOUCHER")
        node = "%032x" % 100
        M.register_seller(node, "069999999", True, "", T0)
        M.verify_seller(TREASURER, node, T0)
        self.assertFalse(M.voucher_available(node, T0), "off automatically at 100")
        self.assertEqual("INVOICE", M.submit_listing(node, fields(title="Objet 100"), T0, pay_with="AUTO")["paid_with"])

    def test_a_rejected_voucher_listing_gives_the_voucher_back(self):
        M = fresh()
        seller(M)
        M.verify_seller(TREASURER, SELLER, T0)
        out = submit(M, pay_with="VOUCHER")
        M.review(OPERATOR, out["listing"]["id"], False, "no", T0)
        self.assertTrue(M.voucher_available(SELLER, T0))

    def test_auto_prefers_voucher_then_package_then_invoice(self):
        M = fresh()
        seller(M)
        self.assertEqual("INVOICE", submit(M, pay_with="AUTO")["paid_with"], "nothing else available")
        inv = M.buy_package(SELLER, "PACKAGE5", "MTN", T0)["invoice"]
        pay(M, inv["id"], txn="TXN-P")
        self.assertTrue(M.seller_status(SELLER, T0)["verified"], "a matched invoice verified the phone")
        self.assertEqual("VOUCHER", submit(M, pay_with="AUTO", title="B")["paid_with"], "the free one first")
        self.assertEqual("PACKAGE", submit(M, pay_with="AUTO", title="C")["paid_with"], "then a prepaid slot")
        self.assertEqual(4, M.my_packages(SELLER, T0)[0]["slots_free"])


class PackageTest(unittest.TestCase):

    def test_slot_consumed_only_at_publication_and_restored_on_rejection(self):
        M = fresh()
        seller(M)
        inv = M.buy_package(SELLER, "PACKAGE5", "MTN", T0)["invoice"]
        self.assertEqual(40_000, inv["amount"])
        with self.assertRaises(MarketError) as cm:
            submit(M, pay_with="PACKAGE")
        self.assertEqual(market.NO_SLOT, cm.exception.reason, "nothing before the money")
        pay(M, inv["id"], txn="TXN-P")
        st = M.seller_status(SELLER, T0)
        self.assertEqual(1, len(st["packages"]))
        self.assertEqual((5, 0, 5), (st["packages"][0]["slots_total"], st["packages"][0]["slots_used"], st["packages"][0]["slots_free"]))
        self.assertEqual(T0 + 90 * DAY_MS, st["packages"][0]["valid_until"])
        out = submit(M, pay_with="PACKAGE")
        self.assertEqual(AWAITING_REVIEW, out["listing"]["state"])
        pk = M.my_packages(SELLER, T0)[0]
        self.assertEqual(0, pk["slots_used"], "not consumed before publication")
        self.assertEqual(4, pk["slots_free"], "but reserved, so it cannot be counted on twice")
        M.review(OPERATOR, out["listing"]["id"], True, "", T0)
        self.assertEqual(1, M.my_packages(SELLER, T0)[0]["slots_used"])
        # a rejection before publication consumed nothing
        out2 = submit(M, pay_with="PACKAGE", title="B")
        M.review(OPERATOR, out2["listing"]["id"], False, "no", T0)
        self.assertEqual((1, 4), (M.my_packages(SELLER, T0)[0]["slots_used"], M.my_packages(SELLER, T0)[0]["slots_free"]))
        # a trusted seller's flagged publication consumed one, and a rejection restores it
        M.set_trusted(OPERATOR, SELLER, True, T0)
        out3 = submit(M, pay_with="PACKAGE", title="C")
        self.assertEqual(PUBLISHED, out3["listing"]["state"])
        self.assertEqual(2, M.my_packages(SELLER, T0)[0]["slots_used"])
        M.review(OPERATOR, out3["listing"]["id"], False, "no", T0)
        self.assertEqual(1, M.my_packages(SELLER, T0)[0]["slots_used"], "restored")
        self.assertEqual(1, len(M.posted.rows), "the package fee was posted once and never refunded")

    def test_five_slots_then_the_sixth_needs_an_invoice(self):
        M = fresh()
        seller(M)
        pay(M, M.buy_package(SELLER, "PACKAGE5", "MTN", T0)["invoice"]["id"], txn="TXN-P")
        for i in range(5):
            out = submit(M, pay_with="PACKAGE", title="Objet %d" % i)
            M.review(OPERATOR, out["listing"]["id"], True, "", T0)
        self.assertEqual([], M.my_packages(SELLER, T0), "exhausted")
        with self.assertRaises(MarketError):
            submit(M, pay_with="PACKAGE", title="Six")
        # the package payment verified the phone, so the launch voucher comes first; then it is invoices
        self.assertEqual("VOUCHER", submit(M, pay_with="AUTO", title="Six")["paid_with"])
        self.assertEqual("INVOICE", submit(M, pay_with="AUTO", title="Sept")["paid_with"])

    def test_storefront_has_thirty_slots_for_thirty_days_and_a_catalog(self):
        M = fresh()
        seller(M)
        inv = M.buy_package(SELLER, "STOREFRONT", "MTN", T0)["invoice"]
        self.assertEqual(200_000, inv["amount"])
        pay(M, inv["id"], txn="TXN-S")
        st = M.seller_status(SELLER, T0)
        self.assertTrue(st["storefront"])
        self.assertEqual((30, T0 + 30 * DAY_MS, True), (st["packages"][0]["slots_total"], st["packages"][0]["valid_until"], st["packages"][0]["catalog"]))
        self.assertTrue(M.seller_history(SELLER, T0)["storefront"])
        M.sweep(T0 + 30 * DAY_MS)
        self.assertFalse(M.seller_status(SELLER, T0 + 30 * DAY_MS)["storefront"])

    def test_a_slot_on_an_expired_package_cannot_publish(self):
        M = fresh()
        seller(M)
        pay(M, M.buy_package(SELLER, "PACKAGE5", "MTN", T0)["invoice"]["id"], txn="TXN-P")
        out = submit(M, pay_with="PACKAGE", now=T0 + 89 * DAY_MS)
        M.sweep(T0 + 90 * DAY_MS)
        with self.assertRaises(MarketError) as cm:
            M.review(OPERATOR, out["listing"]["id"], True, "", T0 + 91 * DAY_MS)
        self.assertEqual(market.NO_SLOT, cm.exception.reason)


class InvoiceTest(unittest.TestCase):

    def test_invoice_expires_at_exactly_24_h(self):
        M = fresh()
        seller(M)
        inv = submit(M)["invoice"]
        self.assertEqual(T0 + INVOICE_TTL_MS, inv["expires_at"])
        self.assertEqual("En attente de confirmation", M.get_invoice(SELLER, inv["id"], T0 + INVOICE_TTL_MS - 1)["text"])
        self.assertEqual("Expiré", M.get_invoice(SELLER, inv["id"], T0 + INVOICE_TTL_MS)["text"], "the screen says so even before the sweep")
        with self.assertRaises(MarketError) as cm:
            pay(M, inv["id"], now=T0 + INVOICE_TTL_MS)
        self.assertEqual(market.INVOICE_EXPIRED, cm.exception.reason)
        self.assertEqual(1, M.sweep(T0 + INVOICE_TTL_MS)["invoices_expired"])
        self.assertEqual(EXPIRED, M.get_invoice(SELLER, inv["id"], T0 + INVOICE_TTL_MS)["state"])
        # one millisecond earlier it pays
        M2 = fresh()
        seller(M2)
        inv2 = submit(M2)["invoice"]
        self.assertEqual(PAID, pay(M2, inv2["id"], now=T0 + INVOICE_TTL_MS - 1)["invoice"]["state"])

    def test_an_expired_invoice_needs_a_new_one(self):
        M = fresh()
        seller(M)
        out = submit(M)
        M.sweep(T0 + INVOICE_TTL_MS)
        inv2 = M.pay_listing(SELLER, out["listing"]["id"], "MTN", T0 + INVOICE_TTL_MS)["invoice"]
        self.assertNotEqual(out["invoice"]["reference"], inv2["reference"])
        pay(M, inv2["id"], now=T0 + INVOICE_TTL_MS + 1)
        self.assertEqual(AWAITING_REVIEW, M.get_listing(out["listing"]["id"], T0, who=SELLER)["state"])

    def test_same_operator_txn_twice_refused(self):
        M = fresh()
        seller(M)
        a = submit(M)["invoice"]
        b = submit(M, title="B")["invoice"]
        pay(M, a["id"], txn="MP240926.1234")
        with self.assertRaises(MarketError) as cm:
            pay(M, b["id"], txn="MP240926.1234")
        self.assertEqual(market.TXN_USED, cm.exception.reason)
        self.assertEqual(OPEN, M.get_invoice(SELLER, b["id"], T0)["state"])
        # and paying the same invoice again is refused as well
        with self.assertRaises(MarketError) as cm:
            pay(M, a["id"], txn="MP-other")
        self.assertEqual(market.WRONG_STATE, cm.exception.reason)
        self.assertEqual(1, len(M.posted.rows), "one posting")

    def test_amount_mismatch_refused(self):
        M = fresh()
        seller(M)
        inv = submit(M)["invoice"]
        for wrong in (9_900, 10_100, 20_000, 0):
            with self.assertRaises(MarketError) as cm:
                pay(M, inv["id"], amount=wrong)
            self.assertEqual(market.AMOUNT_MISMATCH, cm.exception.reason)
        self.assertEqual(OPEN, M.get_invoice(SELLER, inv["id"], T0)["state"])
        self.assertTrue(any(r["action"] == "market.confirm" and not r["allowed"] for r in M.audit_rows(TREASURER, T0)))

    def test_only_a_treasurer_confirms_and_the_rail_must_be_real(self):
        M = fresh()
        seller(M)
        inv = M.submit_listing(SELLER, fields(), T0, pay_with="INVOICE", rail="ANY")["invoice"]
        with self.assertRaises(MarketError) as cm:
            M.confirm_invoice(OPERATOR, inv["id"], "T1", 10_000, "", T0, rail="MTN")
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(MarketError) as cm:
            M.confirm_invoice(TREASURER, inv["id"], "T1", 10_000, "", T0, rail="")
        self.assertEqual(market.UNKNOWN_RAIL, cm.exception.reason)
        with self.assertRaises(MarketError):
            M.confirm_invoice(TREASURER, inv["id"], "", 10_000, "", T0, rail="MTN")

    def test_seller_verified_on_first_matched_invoice(self):
        M = fresh()
        seller(M)
        self.assertFalse(M.seller_status(SELLER, T0)["verified"])
        inv = submit(M)["invoice"]
        pay(M, inv["id"], payer="066 12 34 56")
        self.assertTrue(M.seller_status(SELLER, T0)["verified"])
        self.assertTrue(M.seller_history(SELLER, T0)["verified"])
        # paid from somebody else's number: the invoice is paid, the seller is not verified
        M2 = fresh()
        seller(M2)
        pay(M2, submit(M2)["invoice"]["id"], payer="055000000")
        self.assertFalse(M2.seller_status(SELLER, T0)["verified"])

    def test_refund_needs_a_second_different_treasurer(self):
        M = fresh()
        seller(M)
        out = submit(M)
        pay(M, out["invoice"]["id"])
        iid = out["invoice"]["id"]
        with self.assertRaises(MarketError):
            M.refund_invoice(TREASURER, iid, "x", T0), "nothing to refund on a PAID invoice"
        M.review(OPERATOR, out["listing"]["id"], False, "no", T0)
        first = M.refund_invoice(TREASURER, iid, "", T0)
        self.assertTrue(first["needs_second_approver"])
        self.assertEqual(REFUND_REQUESTED, first["state"])
        with self.assertRaises(MarketError) as cm:
            M.refund_invoice(TREASURER, iid, "MP-ref", T0)
        self.assertEqual(market.SAME_APPROVER, cm.exception.reason)
        with self.assertRaises(MarketError) as cm:
            M.refund_invoice(OPERATOR, iid, "MP-ref", T0)
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(MarketError):
            M.refund_invoice(TREASURER2, iid, "", T0), "the send's reference is the evidence"
        done = M.refund_invoice(TREASURER2, iid, "MP-ref", T0 + HOUR_MS)
        self.assertEqual(REFUNDED, done["state"])
        self.assertEqual("Remboursé", done["invoice"]["text"])
        self.assertEqual(2, len(M.posted.rows))
        self.assertEqual(("MARKET_REFUND", "market:revenue", "float:mtn", 10_000, iid),
                         tuple(M.posted.rows[1][k] for k in ("kind", "debit", "credit", "amount", "ref")))
        with self.assertRaises(MarketError):
            M.refund_invoice(TREASURER, iid, "again", T0), "refunded once"

    def test_match_message_by_reference_then_by_amount_never_ambiguous(self):
        M = fresh()
        seller(M)
        a = submit(M)["invoice"]
        b = submit(M, title="B")["invoice"]
        # reference in the text: exactly one candidate, confirmed when a txn id is given
        out = M.match_message(TREASURER, "Vous avez recu 100 FCFA de 066123456. Motif: %s. ID: MP1" % a["reference"].lower(), 10_000, T0,
                              operator_txn_id="MP1", payer_hash=msisdn_hash(PHONE), rail="MTN")
        self.assertEqual("reference", out["by"])
        self.assertEqual(PAID, out["confirmed"]["state"])
        self.assertTrue(M.seller_status(SELLER, T0)["verified"])
        # amount alone with several open invoices: candidates, nothing paid
        c = submit(M, title="C")["invoice"]
        out = M.match_message(TREASURER, "Vous avez recu 100 FCFA", 10_000, T0, operator_txn_id="MP2", rail="MTN")
        self.assertEqual("amount", out["by"])
        self.assertEqual(2, len(out["candidates"]))
        self.assertIsNone(out["confirmed"])
        self.assertEqual(OPEN, M.get_invoice(SELLER, b["id"], T0)["state"])
        # amount alone with exactly one open invoice at that amount: confirmed
        M.cancel_invoice(SELLER, c["id"], T0)
        out = M.match_message(TREASURER, "Vous avez recu 100 FCFA", 10_000, T0, operator_txn_id="MP2", rail="MTN")
        self.assertEqual(PAID, out["confirmed"]["state"])
        # nothing matches: says so
        out = M.match_message(TREASURER, "Vous avez recu 700 FCFA", 70_000, T0, operator_txn_id="MP3", rail="MTN")
        self.assertEqual("none", out["by"])
        self.assertIsNone(out["confirmed"])

    def test_cancel_only_open_and_only_own(self):
        M = fresh()
        seller(M)
        inv = submit(M)["invoice"]
        with self.assertRaises(MarketError):
            M.cancel_invoice(BUYER, inv["id"], T0)
        M.cancel_invoice(SELLER, inv["id"], T0)
        with self.assertRaises(MarketError):
            M.cancel_invoice(SELLER, inv["id"], T0)


class BoostTest(unittest.TestCase):

    def test_boost_needs_published_paid_and_a_zone_and_is_labelled(self):
        M = fresh()
        seller(M)
        out = submit(M)
        lid = out["listing"]["id"]
        with self.assertRaises(MarketError) as cm:
            M.request_boost(SELLER, lid, "Bacongo", "MTN", T0)
        self.assertEqual(market.WRONG_STATE, cm.exception.reason, "not published")
        pay(M, out["invoice"]["id"])
        M.review(OPERATOR, lid, True, "", T0)
        with self.assertRaises(MarketError) as cm:
            M.request_boost(SELLER, lid, "", "MTN", T0)
        self.assertEqual(market.NO_ZONE, cm.exception.reason)
        inv = M.request_boost(SELLER, lid, "Bacongo", "MTN", T0)["invoice"]
        self.assertEqual((20_000, "BOOST", "Bacongo"), (inv["amount"], inv["service"], inv["zone"]))
        self.assertFalse(M.search(T0)["items"][0]["boosted"], "not before the money")
        pay(M, inv["id"], txn="TXN-B", now=T0 + HOUR_MS)
        item = M.search(T0 + HOUR_MS)["items"][0]
        self.assertTrue(item["boosted"])
        self.assertEqual("Bacongo", item["boost_zone"])
        self.assertEqual(T0 + HOUR_MS + 7 * DAY_MS, item["boosted_until"])
        self.assertTrue(M.search(T0 + HOUR_MS, neighbourhood="Bacongo")["items"][0]["boosted"])
        # a boost is for its zone: a Poto-Poto listing boosted for Bacongo is organic in a Poto-Poto search
        other = published(M, title="Autre quartier", neighbourhood="Poto-Poto", txn="TXN-O")
        pay(M, M.request_boost(SELLER, other, "Bacongo", "MTN", T0)["invoice"]["id"], txn="TXN-OB", now=T0 + HOUR_MS)
        self.assertFalse(M.search(T0 + HOUR_MS, neighbourhood="Poto-Poto")["items"][0]["boosted"], "a boost is for its zone")
        self.assertEqual(2, sum(1 for it in M.search(T0 + HOUR_MS)["items"] if it["boosted"]), "citywide, both are marked")
        M.sweep(T0 + HOUR_MS + 7 * DAY_MS)
        self.assertFalse(M.search(T0 + HOUR_MS + 7 * DAY_MS)["items"][0]["boosted"])
        self.assertEqual("BOOST", M.posted.rows[1]["memo"])

    def test_ranking_keeps_organic_results(self):
        M = fresh()
        seller(M)
        organic = [published(M, now=T0 + i * HOUR_MS, txn="TXN-%d" % i, title="Organique %d" % i) for i in range(10)]
        boosted = []
        for i in range(5):
            lid = published(M, now=T0 - DAY_MS + i * HOUR_MS, txn="TXN-B%d" % i, title="Sponsorisé %d" % i)
            inv = M.request_boost(SELLER, lid, "Bacongo", "MTN", T0 + DAY_MS)["invoice"]
            pay(M, inv["id"], txn="TXN-BB%d" % i, now=T0 + DAY_MS)
            boosted.append(lid)
        items = M.search(T0 + DAY_MS, limit=50)["items"]
        self.assertEqual(15, len(items))
        flags = [it["boosted"] for it in items]
        # one boosted per block of four while organic results remain; the rest of the
        # boosted ones follow only once every organic result has been shown
        self.assertEqual([True, False, False, False] * 3 + [True, False, True], flags)
        for start in range(0, 12, 4):
            self.assertEqual(1, sum(flags[start:start + 4]))
        self.assertEqual(10, sum(1 for f in flags if not f), "every organic result is still there")
        # organic ranking is by recency: the newest first
        org = [it["title"] for it in items if not it["boosted"]]
        self.assertEqual("Organique 9", org[0])
        # the first page of 4 shows three organic results
        page = M.search(T0 + DAY_MS, limit=4)["items"]
        self.assertEqual(3, sum(1 for it in page if not it["boosted"]))

    def test_filters_and_distance(self):
        M = fresh()
        seller(M)
        far = published(M, title="Frigo", category="HOME", price_centimes=5_000_000, lat=4.30, lon=15.30, txn="T1", neighbourhood="Talangaï")
        near = published(M, title="Frigo petit", category="HOME", price_centimes=3_000_000, lat=4.2813, lon=15.2614, txn="T2", neighbourhood="Bacongo")
        published(M, title="Robe", category="FASHION", price_centimes=500_000, txn="T3", neighbourhood="Poto-Poto")
        r = M.search(T0, category="HOME")
        self.assertEqual(2, len(r["items"]))
        self.assertEqual(1, len(M.search(T0, category="HOME", max_price=4_000_000)["items"]))
        self.assertEqual(1, len(M.search(T0, q="frigo petit")["items"]))
        self.assertEqual(1, len(M.search(T0, neighbourhood="bacongo")["items"]))
        r = M.search(T0, lat=4.2813, lon=15.2614, category="HOME")
        self.assertEqual(near, r["items"][0]["id"], "nearer first")
        self.assertLess(r["items"][0]["distance_km"], r["items"][1]["distance_km"])
        # positions are rounded to the 0.005 grid, never exact
        self.assertEqual(4.28, M.get_listing(near, T0)["approx_lat"])


class ContentTest(unittest.TestCase):

    def test_prohibited_content_refused_with_reason(self):
        M = fresh()
        seller(M)
        for title, kind in (("Pistolet 9mm", "weapons"), ("Cannabis de qualité", "drugs"), ("Téléphone volé pas cher", "stolen"),
                            ("Sac Vuitton réplique", "counterfeit"), ("Massage érotique", "sexual_services"), ("CNI à vendre", "identity_documents"),
                            ("Passeport congolais", "identity_documents")):
            with self.assertRaises(MarketError) as cm:
                submit(M, title=title)
            self.assertEqual(market.PROHIBITED_CONTENT, cm.exception.reason, title)
            self.assertIn(kind, str(cm.exception), title)
        with self.assertRaises(MarketError) as cm:
            submit(M, category="WEAPONS")
        self.assertEqual(market.PROHIBITED_CONTENT, cm.exception.reason)
        # whole words only: an army jacket is not a weapon, a drugged person is not drugs
        self.assertEqual(AWAITING_PAYMENT, submit(M, title="Veste armée kaki")["listing"]["state"])
        self.assertEqual(0, len([r for r in M.my_listings(SELLER, T0) if r["state"] == REJECTED]))
        with self.assertRaises(MarketError):
            submit(M, category="UNKNOWN_CAT")

    def test_phone_number_and_id_text_refused(self):
        M = fresh()
        seller(M)
        for text in ("Appelez le 066123456", "Tel 06 61 23 45 6", "+242 06 612 34 56", "whatsapp 066.123.456"):
            with self.assertRaises(MarketError) as cm:
                submit(M, description=text)
            self.assertEqual(market.PRIVATE_DATA, cm.exception.reason, text)
        with self.assertRaises(MarketError) as cm:
            submit(M, description="Carte d'identité disponible")
        self.assertEqual(market.PROHIBITED_CONTENT, cm.exception.reason)
        # ordinary numbers are fine
        self.assertEqual(AWAITING_PAYMENT, submit(M, description="Année 2019, 12000 km, 4 portes")["listing"]["state"])
        # an edit is checked the same way
        lid = published(M, title="OK", txn="T9")
        with self.assertRaises(MarketError):
            M.edit_listing(SELLER, lid, {"description": "mon numéro 066123456"}, T0)


class ChatReportBlockTest(unittest.TestCase):

    def test_chat_only_between_seller_and_one_buyer_500_chars(self):
        M = fresh()
        seller(M)
        lid = published(M)
        m = M.send_message(BUYER, lid, SELLER, "Bonjour, toujours disponible ?", T0)["message"]
        self.assertEqual(BUYER, m["from_id"])
        M.send_message(SELLER, lid, BUYER, "Oui.", T0 + 1)
        offer = M.send_message(BUYER, lid, SELLER, "Je propose 20 000 F", T0 + 2, offer_centimes=2_000_000)["message"]
        self.assertEqual(2_000_000, offer["offer_centimes"])
        t = M.thread(BUYER, lid, SELLER, T0 + 3)
        self.assertEqual(3, len(t["messages"]))
        inbox = M.inbox(SELLER, T0 + 3)
        self.assertEqual(1, len(inbox))
        self.assertEqual(2, inbox[0]["unread"])
        M.thread(SELLER, lid, BUYER, T0 + 4)
        self.assertEqual(0, M.inbox(SELLER, T0 + 4)[0]["unread"], "read once the seller opened it")
        # two buyers cannot talk to each other through a listing
        with self.assertRaises(MarketError) as cm:
            M.send_message(BUYER, lid, OTHER, "psst", T0)
        self.assertEqual(market.NOT_A_PARTY, cm.exception.reason)
        with self.assertRaises(MarketError) as cm:
            M.send_message(BUYER, lid, SELLER, "x" * 501, T0)
        self.assertEqual(market.TOO_LONG, cm.exception.reason)
        M.send_message(BUYER, lid, SELLER, "x" * 500, T0)
        with self.assertRaises(MarketError):
            M.send_message("", lid, SELLER, "anon", T0)
        # not on a listing that is not public
        M.withdraw_listing(SELLER, lid, T0 + 5)
        with self.assertRaises(MarketError):
            M.send_message(OTHER, lid, SELLER, "encore là ?", T0 + 6)

    def test_block_stops_the_conversation_both_ways(self):
        M = fresh()
        seller(M)
        lid = published(M)
        M.send_message(BUYER, lid, SELLER, "Bonjour", T0)
        M.block(SELLER, BUYER, True, T0)
        for a, b in ((BUYER, SELLER), (SELLER, BUYER)):
            with self.assertRaises(MarketError) as cm:
                M.send_message(a, lid, b, "…", T0 + 1)
            self.assertEqual(market.BLOCKED, cm.exception.reason)
        self.assertTrue(M.thread(BUYER, lid, SELLER, T0)["blocked"])
        M.block(SELLER, BUYER, False, T0 + 2)
        M.send_message(BUYER, lid, SELLER, "Merci", T0 + 3)

    def test_safety_report_hides_at_once_and_the_operator_resolves(self):
        M = fresh()
        seller(M)
        lid = published(M)
        with self.assertRaises(MarketError):
            M.report(SELLER, T0, listing_id=lid, kind="SAFETY")
        spam = M.report(BUYER, T0, listing_id=lid, kind="SPAM", text="doublon")
        self.assertFalse(spam["hidden"])
        self.assertEqual(PUBLISHED, M.get_listing(lid, T0)["state"])
        r = M.report(OTHER, T0, listing_id=lid, kind="SAFETY", text="adresse dangereuse")
        self.assertTrue(r["hidden"])
        self.assertEqual(HIDDEN, M.get_listing(lid, T0, who=SELLER)["state"])
        self.assertEqual("Masquée", M.get_listing(lid, T0, who=SELLER)["state_text"])
        with self.assertRaises(MarketError):
            M.get_listing(lid, T0)
        self.assertEqual(2, len(M.open_reports(OPERATOR, T0)))
        M.resolve_report(OPERATOR, r["report_id"], "restore", T0 + HOUR_MS)
        self.assertEqual(PUBLISHED, M.get_listing(lid, T0 + HOUR_MS)["state"])
        M.resolve_report(OPERATOR, spam["report_id"], "reject", T0 + HOUR_MS)
        self.assertEqual(REJECTED, M.get_listing(lid, T0 + HOUR_MS, who=SELLER)["state"])
        self.assertEqual(PAID, M.my_invoices(SELLER, T0)[0]["state"], "approved and served: no refund")
        self.assertEqual(0, len(M.open_reports(OPERATOR, T0 + HOUR_MS)))

    def test_blocking_a_seller_hides_their_listings(self):
        M = fresh()
        seller(M)
        lid = published(M)
        M.block_seller(OPERATOR, SELLER, True, T0)
        self.assertEqual(0, len(M.search(T0)["items"]))
        with self.assertRaises(MarketError) as cm:
            submit(M, title="encore")
        self.assertEqual(market.SELLER_BLOCKED, cm.exception.reason)


class PhotoTest(unittest.TestCase):

    def test_photos_jpeg_only_300kb_five_per_listing_served_by_path(self):
        media = tempfile.mkdtemp(prefix="prokmedia")
        M = fresh(media_dir=media)
        seller(M)
        lid = submit(M)["listing"]["id"]
        with self.assertRaises(MarketError) as cm:
            M.store_photo(SELLER, lid, b"\x89PNG" + b"\x00" * 50, T0)
        self.assertEqual(market.NOT_JPEG, cm.exception.reason)
        with self.assertRaises(MarketError) as cm:
            M.store_photo(SELLER, lid, JPEG + b"\x00" * (300 * 1024), T0)
        self.assertEqual(413, cm.exception.code)
        with self.assertRaises(MarketError):
            M.store_photo(BUYER, lid, JPEG, T0)
        for n in range(1, 6):
            self.assertEqual(n, M.store_photo(SELLER, lid, JPEG, T0)["n"])
        with self.assertRaises(MarketError) as cm:
            M.store_photo(SELLER, lid, JPEG, T0)
        self.assertEqual(market.PHOTO_LIMIT, cm.exception.reason)
        self.assertEqual(os.path.join(media, lid, "3.jpg"), M.photo_path(lid, 3))
        self.assertIsNone(M.photo_path(lid, 6))
        self.assertIsNone(M.photo_path(lid, "../x"))
        self.assertIsNone(M.photo_path("nope", 1))
        self.assertEqual(5, len(M.get_listing(lid, T0, who=SELLER)["photo_urls"]))

    def test_a_photo_added_to_a_published_listing_is_a_material_edit(self):
        M = fresh()
        seller(M)
        lid = published(M)
        M.store_photo(SELLER, lid, JPEG, T0 + 1)
        self.assertEqual(AWAITING_REVIEW, M.get_listing(lid, T0 + 1, who=SELLER)["state"])


class SweepTest(unittest.TestCase):

    def test_sweep_expires_invoices_listings_boosts_and_packages(self):
        M = fresh()
        seller(M)
        lid = published(M)
        submit(M, title="never paid")
        inv = M.request_boost(SELLER, lid, "Bacongo", "MTN", T0)["invoice"]
        pay(M, inv["id"], txn="TB")
        pay(M, M.buy_package(SELLER, "PACKAGE5", "MTN", T0)["invoice"]["id"], txn="TP")
        self.assertEqual({"invoices_expired": 0, "listings_expired": 0, "boosts_ended": 0, "packages_expired": 0}, M.sweep(T0 + HOUR_MS))
        n = M.sweep(T0 + INVOICE_TTL_MS)
        self.assertEqual(1, n["invoices_expired"])
        n = M.sweep(T0 + 7 * DAY_MS)
        self.assertEqual(1, n["boosts_ended"])
        n = M.sweep(T0 + 30 * DAY_MS)
        self.assertEqual(1, n["listings_expired"])
        self.assertEqual(EXPIRED, M.get_listing(lid, T0 + 30 * DAY_MS, who=SELLER)["state"])
        self.assertEqual(0, len(M.search(T0 + 30 * DAY_MS)["items"]))
        n = M.sweep(T0 + 90 * DAY_MS)
        self.assertEqual(1, n["packages_expired"])
        self.assertEqual([], M.my_packages(SELLER, T0 + 90 * DAY_MS))
        # idempotent
        self.assertEqual({"invoices_expired": 0, "listings_expired": 0, "boosts_ended": 0, "packages_expired": 0}, M.sweep(T0 + 90 * DAY_MS))


class DispatchTest(unittest.TestCase):

    def test_public_gets_work_unsigned_and_everything_else_needs_a_signature(self):
        M = fresh()
        seller(M)
        out = submit(M)
        lid = out["listing"]["id"]
        M.store_photo(SELLER, lid, JPEG, T0)
        pay(M, out["invoice"]["id"])
        M.review(OPERATOR, lid, True, "", T0)
        code, out = M.handle_get("", "/v1/market/prices", {}, T0)
        self.assertEqual(200, code)
        self.assertEqual(10_000, out["prices"]["POST"]["centimes"])
        code, out = M.handle_get("", "/v1/market/search?q=v%C3%A9lo", {"q": "vélo"}, T0)
        self.assertEqual((200, 1), (code, len(out["items"])))
        code, out = M.handle_get("", "/v1/market/browse", {"category": "OTHER"}, T0)
        self.assertEqual((200, 1), (code, len(out["items"])))
        code, out = M.handle_get("", "/v1/market/listing", {"id": lid}, T0)
        self.assertEqual(200, code)
        self.assertEqual(1, out["seller"]["published_count"])
        self.assertEqual(T0, out["seller"]["member_since"])
        self.assertNotIn("review_note", out, "the public view carries no moderation note")
        code, out = M.handle_get("", "/v1/market/photo", {"listing": lid, "n": "1"}, T0)
        self.assertEqual((200, "image/jpeg"), (code, out["content_type"]))
        self.assertTrue(os.path.isfile(out["path"]))
        self.assertEqual(404, M.handle_get("", "/v1/market/photo", {"listing": lid, "n": "9"}, T0)[0])
        for p in ("/v1/market/me", "/v1/market/my/listings", "/v1/market/my/invoices", "/v1/market/inbox", "/v1/market/operator/queue",
                  "/v1/market/treasury/invoices"):
            self.assertEqual(401, M.handle_get("", p, {}, T0)[0], p)
        self.assertEqual(401, M.handle_post("", "/v1/market/listing", fields(), T0)[0])
        self.assertEqual(403, M.handle_get(SELLER, "/v1/market/operator/queue", {}, T0)[0])
        self.assertEqual(403, M.handle_get(SELLER, "/v1/market/treasury/invoices", {}, T0)[0])
        self.assertEqual(404, M.handle_get(SELLER, "/v1/market/nothing", {}, T0)[0])

    def test_the_whole_flow_through_the_dispatchers(self):
        M = fresh()
        code, out = M.handle_post(SELLER, "/v1/market/seller", {"phone": PHONE, "adult_attested": True}, T0)
        self.assertEqual((200, True), (code, out["registered"]))
        code, out = M.handle_post(SELLER, "/v1/market/listing", dict(fields(), pay_with="INVOICE", rail="MTN"), T0)
        self.assertEqual(200, code)
        lid, inv = out["listing"]["id"], out["invoice"]
        self.assertEqual("En attente de confirmation", inv["text"])
        code, out = M.handle_post(SELLER, "/v1/market/photo?listing=" + lid, JPEG, T0)
        self.assertEqual((200, 1), (code, out["n"]))
        self.assertEqual(415, M.handle_post(SELLER, "/v1/market/photo?listing=" + lid, {"nope": 1}, T0)[0])
        code, out = M.handle_post(TREASURER, "/v1/market/treasury/match", {"text": "recu 100 F motif " + inv["reference"], "amount": 10_000,
                                                                              "operator_txn_id": "MP9", "payer_hash": msisdn_hash(PHONE), "rail": "MTN"}, T0)
        self.assertEqual((200, "Payé"), (code, out["confirmed"]["text"]))
        code, out = M.handle_get(SELLER, "/v1/market/my/listings", {}, T0)
        self.assertEqual("En attente de modération", out["listings"][0]["state_text"])
        code, out = M.handle_get(OPERATOR, "/v1/market/operator/queue", {}, T0)
        self.assertEqual(1, len(out["listings"]))
        code, out = M.handle_post(OPERATOR, "/v1/market/operator/review", {"listing": lid, "approve": True}, T0)
        self.assertEqual((200, "Publiée"), (code, out["listing"]["state_text"]))
        code, out = M.handle_post(BUYER, "/v1/market/message", {"listing": lid, "to": SELLER, "body": "Offre", "offer_centimes": 1_000_000}, T0)
        self.assertEqual(200, code)
        code, out = M.handle_get(SELLER, "/v1/market/thread", {"listing": lid, "other": BUYER}, T0)
        self.assertEqual(1_000_000, out["messages"][0]["offer_centimes"])
        code, out = M.handle_post(SELLER, "/v1/market/listing/boost", {"id": lid, "zone": "Bacongo", "rail": "AIRTEL"}, T0)
        self.assertEqual((200, 20_000), (code, out["invoice"]["amount"]))
        code, out = M.handle_post(TREASURER, "/v1/market/treasury/confirm", {"invoice": out["invoice"]["id"], "operator_txn_id": "AM1", "amount_seen": 20_000, "rail": "AIRTEL"}, T0)
        self.assertEqual(200, code)
        self.assertEqual("float:airtel", M.posted.rows[-1]["debit"])
        self.assertTrue(M.handle_get("", "/v1/market/search", {}, T0)[1]["items"][0]["boosted"])
        code, out = M.handle_post(SELLER, "/v1/market/listing/withdraw", {"id": lid}, T0)
        self.assertEqual((200, "Retirée"), (code, out["listing"]["state_text"]))
        code, out = M.handle_post(SELLER, "/v1/market/listing", dict(fields(title="Pistolet"), pay_with="INVOICE"), T0)
        self.assertEqual((400, market.PROHIBITED_CONTENT), (code, out["reason"]))
        self.assertIn("refusé", out["error"])
        self.assertEqual(404, M.handle_post(SELLER, "/v1/market/nothing", {}, T0)[0])


if __name__ == "__main__":
    unittest.main()
