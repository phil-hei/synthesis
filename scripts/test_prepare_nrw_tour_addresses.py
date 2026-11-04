import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


def module(name):
    path = Path(__file__).with_name(name + ".py")
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


prepare = module("prepare-nrw-tour-addresses")
serve = module("serve-local-tour-geocoder")


class NrwTourAddressTest(unittest.TestCase):
    def test_formatting_normalization_never_changes_house_numbers_or_repairs_typos(self):
        self.assertEqual(prepare.address_key("Rödinghausen", "Wagnerweg 3"),
                         prepare.address_key("Roedinghausen", "Wagner weg 3"))
        self.assertEqual(prepare.address_key("Teststadt", "Helene-Weigel-Straße 9"),
                         prepare.address_key("Teststadt", "Helene Weigelstr 9"))
        self.assertEqual(prepare.address_key("Teststadt", "Dalbker Straße 31"),
                         prepare.address_key("Teststadt", "Dalbker str . 31"))
        self.assertNotEqual(prepare.address_key("Teststadt", "Weg 70-72"),
                            prepare.address_key("Teststadt", "Weg 7072"))
        self.assertNotEqual(prepare.address_key("Teststadt", "Bauernrott 37A"),
                            prepare.address_key("Teststadt", "Bauenrott 37a"))
        self.assertNotEqual(prepare.address_key("Herzebrock", "Weg 2"),
                            prepare.address_key("Herzebrock-Clarholz", "Weg 2"))
        self.assertEqual(prepare.address_key("Herzebrock", "Weg 2", {prepare.normal("Herzebrock"): "Herzebrock-Clarholz"}),
                         prepare.address_key("Herzebrock-Clarholz", "Weg 2"))

    def test_exact_house_numbers_preserve_candidates_and_ignore_unreliable_quality(self):
        class Transform:
            def transform(self, x, y):
                return x / 100000, y / 100000
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.zip"
            rows = []
            for quality, east in [("A", "700000"), ("A", "710000"), ("B", "720000")]:
                rows.append(";".join(["N", "ref-" + east, quality, "05", "NRW", "9", "Arnsberg", "99", "Kreis", "001", "Teststadt", "0000", "", "00001", "Teststraße", "21", "a", "32", east, "5100000", "2026-07-01"]))
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("gebref.txt", "\n".join(rows))
            draft = {"tours": [{"stops": [{"location": {"street": "Teststr. 21a", "postalCode": "01234", "city": "Teststadt, Westf"}},
                                          {"location": {"street": "Teststr. 21b", "postalCode": "01234", "city": "Teststadt"}}]}]}
            index, report = prepare.prepare(path, draft, [], Transform())
            self.assertEqual([2, 0], [len(v) for v in index.values()])
            self.assertEqual(1, report["ambiguous"])
            self.assertFalse(report["postalCodesVerified"])
            self.assertFalse(report["truckEntrancesVerified"])
            parcel_index, parcel_report = prepare.prepare(path, draft, [], Transform(), qualities=("B",))
            self.assertEqual([1, 0], [len(v) for v in parcel_index.values()])
            self.assertEqual(["B"], parcel_report["acceptedQualities"])
            self.assertIn("Flurstückspunkt", next(iter(parcel_index.values()))[0]["licence"])
            output = Path(temporary) / "index.json"
            output.write_text(json.dumps(index))
            self.assertEqual(index, serve.load_index(output))

    def test_local_index_rejects_invalid_coordinates(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "index.json"
            path.write_text(json.dumps({"Testweg 1": [{"lon": "NaN", "lat": "51", "display_name": "Bad"}]}))
            with self.assertRaises(ValueError):
                serve.load_index(path)


if __name__ == "__main__":
    unittest.main()
