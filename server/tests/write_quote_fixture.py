"""v0.19.0: writes server/tests/fixtures/quote_examples.json - the §10.3 quote examples.

    cd server && python -m tests.write_quote_fixture

The owner's phone shows "le client paie X pour 100 Mo ; vous gagnez Y" and the Brain signs
the same numbers. If the two round differently, the screen lies by a centime and no green
suite on either side would notice. So Python writes the customer totals at 20 MB / 200 MB /
1 GB / 6 GB (5 / 50 / 250 / 1,500 FCFA) with both splits, and BOTH suites read the file:
test_quotes.FixtureTest here, OwnerQuoteViewTest.kt on the phone. Regenerate only when the
rule itself is meant to change, and say so in the report.
"""
import os

from brain import quotes


def main():
    path = os.path.join(os.path.dirname(__file__), "fixtures", "quote_examples.json")
    data = quotes.write_fixture(path)
    print("wrote %d examples to %s" % (len(data["examples"]), path))


if __name__ == "__main__":
    main()
